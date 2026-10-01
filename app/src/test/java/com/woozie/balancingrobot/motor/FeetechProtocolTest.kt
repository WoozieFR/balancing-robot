package com.woozie.balancingrobot.motor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeetechProtocolTest {
    @Test
    fun buildsReferencePingPacket() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 2, 1, 0xFB.toByte()),
            FeetechProtocol.buildPacket(1, FeetechProtocol.INST_PING),
        )
    }

    @Test
    fun parsesStatusAndRejectsBadChecksum() {
        val packet = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 4, 0, 0x09, 0x03, 0xEE.toByte())

        val status = FeetechProtocol.parseStatus(packet)

        assertEquals(1, status!!.servoId)
        assertEquals(0, status.error)
        assertEquals(listOf(0x09.toByte(), 0x03.toByte()), status.parameters.toList())
        assertNull(FeetechProtocol.parseStatus(packet.copyOf().also { it[it.lastIndex] = 0 }))
    }

    @Test
    fun encodesAndDecodesSignMagnitude() {
        assertEquals(100, FeetechProtocol.decodeSignMagnitude(FeetechProtocol.encodeSignMagnitude(100)))
        assertEquals(-100, FeetechProtocol.decodeSignMagnitude(FeetechProtocol.encodeSignMagnitude(-100)))
    }

    @Test
    fun buildsOneSynchronizedVelocityFrame() {
        val frame = FeetechProtocol.buildSyncWrite(
            ids = listOf(6, 7),
            address = FeetechProtocol.GOAL_VELOCITY,
            valueLength = 2,
            values = listOf(100, FeetechProtocol.encodeSignMagnitude(-100)),
        )

        assertEquals(FeetechProtocol.BROADCAST_ID, frame[2].toInt() and 0xFF)
        assertEquals(FeetechProtocol.INST_SYNC_WRITE, frame[4].toInt() and 0xFF)
        assertTrue(frame.size > 10)
    }

    @Test
    fun pwmUsesGoalTimeRegisterAndTenBitSignMagnitude() {
        val frame = FeetechProtocol.buildSyncWrite(
            ids = listOf(6, 7),
            address = FeetechProtocol.GOAL_PWM,
            valueLength = 2,
            values = listOf(
                FeetechProtocol.encodeSignMagnitude(100, FeetechProtocol.PWM_SIGN_BIT),
                FeetechProtocol.encodeSignMagnitude(-100, FeetechProtocol.PWM_SIGN_BIT),
            ),
        )

        assertEquals(FeetechProtocol.GOAL_PWM, frame[5].toInt() and 0xFF)
        assertEquals(100, littleEndianValue(frame.copyOfRange(8, 10)))
        assertEquals(100 or (1 shl FeetechProtocol.PWM_SIGN_BIT), littleEndianValue(frame.copyOfRange(11, 13)))
    }

    @Test
    fun readsPackedSts3215Telemetry() {
        val response = FeetechProtocol.buildPacket(
            servoId = 6,
            instruction = 0,
            parameters = byteArrayOf(
                100, 0, // velocity +100
                0x14, 0x04, // load -20 (sign bit 10)
                75, // 7.5 V
                42, // 42 °C
            ),
        )
        val port = object : FeetechPort {
            override fun flush() = Unit
            override fun write(data: ByteArray, timeoutMs: Int): Int = data.size
            override fun read(maxBytes: Int, timeoutMs: Int): ByteArray = response
            override fun close() = Unit
        }

        val telemetry = FeetechBus(port).readTelemetry(6)

        assertEquals(100, telemetry!!.velocity)
        assertEquals(-20, telemetry.load)
        assertEquals(7.5, telemetry.voltage, 0.001)
        assertEquals(42, telemetry.temperatureC)
    }
}
