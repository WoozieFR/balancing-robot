package com.woozie.balancingrobot.motor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialProber

data class UsbSerialDeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val driverName: String,
    val isCh340: Boolean,
    val permissionGranted: Boolean,
)

/**
 * USB inventory and explicit permission request. It never opens a port by
 * itself; opening remains an explicit Lot 4 operation.
 */
class AndroidUsbDeviceDetector(context: Context) {
    private val context = context.applicationContext
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber.getDefaultProber()

    fun enumerate(): List<UsbSerialDeviceInfo> = usbManager.deviceList.values.mapNotNull { device ->
        val driver = prober.probeDevice(device) ?: return@mapNotNull null
        device.toInfo(driver.javaClass.simpleName, usbManager.hasPermission(device))
    }

    fun requestPermission(deviceId: Int): Boolean {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return false
        if (usbManager.hasPermission(device)) return true
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
        return false
    }

    private fun UsbDevice.toInfo(driverName: String, permissionGranted: Boolean): UsbSerialDeviceInfo =
        UsbSerialDeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            driverName = driverName,
            isCh340 = UsbDeviceClassifier.isCh340(vendorId, productId),
            permissionGranted = permissionGranted,
        )

    companion object {
        const val ACTION_USB_PERMISSION = "com.woozie.balancingrobot.USB_PERMISSION"
    }
}
