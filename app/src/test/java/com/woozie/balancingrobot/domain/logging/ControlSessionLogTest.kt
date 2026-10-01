package com.woozie.balancingrobot.domain.logging

import com.woozie.balancingrobot.domain.model.RobotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSessionLogTest {
    @Test
    fun recordsOnlyBetweenExplicitStartAndStopAndExportsConfiguration() {
        val log = ControlSessionLog(capacity = 2)
        val record = ControlLogRecord(
            timestampNs = 1L,
            receivedTimestampNs = 2L,
            accelX = 0.0,
            accelY = 0.0,
            accelZ = 9.81,
            gyroXDegPerSec = 1.0,
            gyroYDegPerSec = 2.0,
            gyroZDegPerSec = 3.0,
            accelAngleDeg = 0.5,
            estimatedAngleDeg = 0.4,
            gyroRateDegPerSec = 1.0,
            dtSec = 0.005,
            config = RobotConfig(alpha = 0.97, kp = 12.5),
            errorDeg = -0.4,
            rawCommand = -5.0,
            boundedCommand = -5,
            saturated = false,
            motorCommand0 = -5,
            motorCommand1 = 5,
            controlLatencyMs = 1.2,
            armState = "BALANCE_ARMED",
            sampleStatus = null,
        )

        log.append(record)
        assertEquals(0, log.size())
        log.start()
        assertTrue(log.isRecording())
        log.append(record)
        log.stop()
        log.append(record)

        assertFalse(log.isRecording())
        assertEquals(1, log.size())
        val csv = log.toCsv()
        assertTrue(csv.lines().first().contains("alpha"))
        assertTrue(csv.contains(",0.97,"))
    }
}
