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
            effectiveTargetDeg = 1.25,
            speedFilteredCmPerSec = 3.5,
            speedCorrectionDeg = 1.25,
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
        assertTrue(csv.lines().first().contains("speed_filtered_cmps"))
        assertTrue(csv.contains(",0.97,"))
        assertTrue(csv.contains("3.500000000"))
        val rows = csv.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(rows.first().split(',').size, rows[1].split(',').size)
    }

    @Test
    fun exportsSpeedKevAndSpeedTargetSlewInHeaderOrder() {
        val log = ControlSessionLog()
        val config = RobotConfig(
            speedKevDegPerCmPerSec = 0.2,
            speedTargetSlewRateCmPerSec = 15.85,
        )
        val record = ControlLogRecord(
            timestampNs = 1L,
            receivedTimestampNs = 1L,
            accelX = null,
            accelY = null,
            accelZ = null,
            gyroXDegPerSec = null,
            gyroYDegPerSec = null,
            gyroZDegPerSec = null,
            accelAngleDeg = null,
            estimatedAngleDeg = null,
            gyroRateDegPerSec = null,
            dtSec = null,
            config = config,
            errorDeg = null,
            rawCommand = null,
            boundedCommand = null,
            saturated = null,
            motorCommand0 = null,
            motorCommand1 = null,
            controlLatencyMs = null,
            armState = "DISARMED",
            sampleStatus = null,
        )
        log.start()
        log.append(record)
        val rows = log.toCsv().lineSequence().filter { it.isNotBlank() }.toList()
        val header = rows[0].split(',')
        val values = rows[1].split(',')

        assertEquals(
            0.2,
            values[header.indexOf("speed_kev_deg_per_cmps")].toDouble(),
            1e-9,
        )
        assertEquals(
            15.85,
            values[header.indexOf("speed_target_slew_rate_cm_per_sec")].toDouble(),
            1e-9,
        )
    }
}
