package com.woozie.balancingrobot.motor

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class MotorFrame(
    val values: List<Int>,
    val timestampNs: Long = System.nanoTime(),
)

data class MotorIoMetrics(
    val writtenFrames: Long = 0,
    val supersededFrames: Long = 0,
    val telemetryFrames: Long = 0,
    val lastWriteLatencyMs: Double? = null,
)

/**
 * Single-owner motor I/O loop.  It deliberately keeps only the newest control
 * frame; urgent zero/torque-off actions always run first.  No caller may write
 * the Feetech port directly once this scheduler has been started.
 */
class MotorIoScheduler(
    private val bus: FeetechBus,
    private val motorIds: List<Int>,
    private val motorSigns: List<Int>,
    private val vmax: Int,
    private val manualTimeoutMs: Long = 300,
    private val onTelemetry: (List<FeetechTelemetry>) -> Unit,
    private val onFault: (String) -> Unit,
) : Closeable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "robot-motor-io").apply { isDaemon = true }
    }
    private val latestControlFrame = AtomicReference<MotorFrame?>(null)
    private val urgentActions = ConcurrentLinkedQueue<() -> Unit>()
    private var armed = false
    private var lastTelemetryNs = 0L
    private var writtenFrames = 0L
    private var supersededFrames = 0L
    private var telemetryFrames = 0L
    private var lastWriteLatencyMs: Double? = null

    fun start() {
        executor.scheduleWithFixedDelay(::tick, 0, 20, TimeUnit.MILLISECONDS)
    }

    fun arm(): Boolean {
        if (executor.isShutdown) return false
        val future = executor.submit<Boolean> {
            runCatching {
                bus.writeSyncVelocity(motorIds, List(motorIds.size) { 0 })
                bus.writeSyncTorqueEnable(motorIds, true)
                armed = true
                latestControlFrame.set(null)
                true
            }.getOrElse {
                onFault("Armement moteur impossible : ${it.message ?: it.javaClass.simpleName}")
                armed = false
                false
            }
        }
        return runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    fun disarm() {
        if (executor.isShutdown) return
        latestControlFrame.set(null)
        armed = false
        urgentActions += {
            runCatching {
                bus.writeSyncVelocity(motorIds, List(motorIds.size) { 0 })
                bus.writeSyncTorqueEnable(motorIds, false)
            }.onFailure { onFault("Désarmement moteur incomplet : ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun submitManual(value: Int, held: Boolean) {
        val bounded = value.coerceIn(-vmax, vmax)
        if (!armed || !held) {
            latestControlFrame.set(MotorFrame(List(motorIds.size) { 0 }))
            return
        }
        latestControlFrame.set(
            MotorFrame(motorSigns.map { sign -> bounded * sign }),
        )
    }

    fun submitControl(values: List<Int>) {
        if (!armed || values.size != motorIds.size) {
            latestControlFrame.set(MotorFrame(List(motorIds.size) { 0 }))
            return
        }
        latestControlFrame.set(MotorFrame(values.map { it.coerceIn(-vmax, vmax) }))
    }

    fun safetyStop() = disarm()

    fun metrics(): MotorIoMetrics = MotorIoMetrics(
        writtenFrames = writtenFrames,
        supersededFrames = supersededFrames,
        telemetryFrames = telemetryFrames,
        lastWriteLatencyMs = lastWriteLatencyMs,
    )

    private fun tick() {
        if (executor.isShutdown) return
        while (true) {
            val urgent = urgentActions.poll() ?: break
            runCatching { urgent() }.onFailure { onFault("Action moteur impossible : ${it.message}") }
        }

        val frame = latestControlFrame.get()
        if (armed) {
            val output = if (frame != null &&
                System.nanoTime() - frame.timestampNs <= manualTimeoutMs * 1_000_000L
            ) frame.values else List(motorIds.size) { 0 }
            val started = System.nanoTime()
            runCatching { bus.writeSyncVelocity(motorIds, output) }
                .onSuccess {
                    writtenFrames++
                    lastWriteLatencyMs = (System.nanoTime() - started) / 1_000_000.0
                }
                .onFailure { onFault("Écriture vitesse impossible : ${it.message ?: it.javaClass.simpleName}") }
            if (frame != null && frame.timestampNs != latestControlFrame.get()?.timestampNs) {
                supersededFrames++
            }
        }

        val now = System.nanoTime()
        if (now - lastTelemetryNs >= 50_000_000L) {
            lastTelemetryNs = now
            val values = motorIds.map { id ->
                runCatching { bus.readTelemetry(id) }
                    .onFailure { onFault("Télémétrie moteur $id impossible : ${it.message}") }
                    .getOrNull()
            }
            if (values.all { it != null }) {
                telemetryFrames++
                onTelemetry(values.filterNotNull())
            } else if (armed) {
                onFault("Moteur attendu silencieux : arrêt du groupe")
            }
        }
    }

    override fun close() {
        if (!executor.isShutdown) {
            disarm()
            executor.shutdown()
            runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }
    }
}
