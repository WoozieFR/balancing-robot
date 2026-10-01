package com.woozie.balancingrobot.motor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceClassifierTest {
    @Test
    fun identifiesCommonCh340() {
        assertTrue(UsbDeviceClassifier.isCh340(0x1A86, 0x7523))
    }

    @Test
    fun rejectsOtherUsbSerialDevices() {
        assertFalse(UsbDeviceClassifier.isCh340(0x10C4, 0xEA60))
        assertFalse(UsbDeviceClassifier.isCh340(0x1A86, 0x5523))
    }
}
