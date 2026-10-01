package com.woozie.balancingrobot.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.woozie.balancingrobot.domain.model.Vector3
import com.woozie.balancingrobot.domain.sensor.ImuRatePolicy

data class ImuCapabilities(
    val accelerometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
)

interface ImuListener {
    fun onCapabilities(capabilities: ImuCapabilities)
    fun onAccelerometer(values: Vector3, timestampNs: Long, receivedTimestampNs: Long)
    fun onGyroscope(values: Vector3, timestampNs: Long, receivedTimestampNs: Long)
}

/** Android adapter only: it copies SensorEvent values and never performs control or I/O. */
class AndroidImuSource(
    context: Context,
    private val callbackHandler: Handler,
    requestedRateHz: Int = ImuRatePolicy.DEFAULT_HZ,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val samplingPeriodUs = ImuRatePolicy.periodUs(requestedRateHz)
    private var listener: ImuListener? = null
    private var active = false
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.size < 3) return
            val vector = Vector3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
            val received = android.os.SystemClock.elapsedRealtimeNanos()
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> listener?.onAccelerometer(vector, event.timestamp, received)
                Sensor.TYPE_GYROSCOPE -> listener?.onGyroscope(vector, event.timestamp, received)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(listener: ImuListener): ImuCapabilities {
        stop()
        this.listener = listener
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        // Request the configured period. Unlike FASTEST (0 µs), this does not
        // ask Android for an unbounded rate; the driver may still return a
        // lower cadence, which is measured and displayed by the diagnostic UI.
        val accelRegistered = accelerometer?.let(::registerSensor) == true
        val gyroRegistered = gyroscope?.let(::registerSensor) == true
        active = accelRegistered || gyroRegistered
        val capabilities = ImuCapabilities(accelRegistered, gyroRegistered)
        listener.onCapabilities(capabilities)
        return capabilities
    }

    private fun registerSensor(sensor: Sensor): Boolean = runCatching {
        sensorManager.registerListener(
            sensorListener,
            sensor,
            samplingPeriodUs,
            callbackHandler,
        )
    }.getOrDefault(false)

    fun stop() {
        if (active) sensorManager.unregisterListener(sensorListener)
        active = false
        listener = null
    }
}
