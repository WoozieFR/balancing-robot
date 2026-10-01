package com.woozie.balancingrobot.motor

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable

/** Opens a CH340/USB-serial port only when the caller explicitly asks for it. */
class AndroidUsbSerialTransport(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber.getDefaultProber()

    fun open(deviceId: Int, baudRate: Int = 1_000_000): OpenedUsbSerialPort {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: error("USB device not found: $deviceId")
        check(usbManager.hasPermission(device)) { "USB permission required" }
        val driver = prober.probeDevice(device) ?: error("No USB serial driver for ${device.deviceName}")
        val port = driver.ports.firstOrNull() ?: error("USB serial driver has no port")
        val connection = usbManager.openDevice(device) ?: error("Unable to open USB device")
        return try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            OpenedUsbSerialPort(port, connection)
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }
}

class OpenedUsbSerialPort(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
) : FeetechPort {
    override fun flush() {
        // CH34x adapters commonly do not implement the optional hardware
        // purge operation exposed by usb-serial-for-android.  Calling it
        // unconditionally makes an otherwise usable port fail with
        // UnsupportedOperationException before the first PING.  The Feetech
        // protocol is half-duplex and every transaction already waits for a
        // complete response, so a no-op is the safe fallback here.
        runCatching { port.purgeHwBuffers(true, false) }
            .onFailure { error ->
                if (error !is UnsupportedOperationException) throw error
            }
    }

    override fun write(data: ByteArray, timeoutMs: Int): Int {
        port.write(data, timeoutMs)
        return data.size
    }

    override fun read(maxBytes: Int, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        val count = port.read(buffer, timeoutMs)
        return buffer.copyOf(count.coerceAtLeast(0))
    }

    override fun close() {
        runCatching { port.close() }
        connection.close()
    }
}
