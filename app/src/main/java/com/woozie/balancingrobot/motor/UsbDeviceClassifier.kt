package com.woozie.balancingrobot.motor

/** Identifies the common WCH CH340 USB-to-serial VID/PID pair. */
object UsbDeviceClassifier {
    const val CH340_VENDOR_ID = 0x1A86
    const val CH340_PRODUCT_ID = 0x7523

    fun isCh340(vendorId: Int, productId: Int): Boolean =
        vendorId == CH340_VENDOR_ID && productId == CH340_PRODUCT_ID
}
