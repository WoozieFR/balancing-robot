package com.woozie.balancingrobot.motor

import java.io.Closeable

interface FeetechPort : Closeable {
    fun flush()
    fun write(data: ByteArray, timeoutMs: Int): Int
    fun read(maxBytes: Int, timeoutMs: Int): ByteArray
}

data class FeetechPingResult(val modelNumber: Int, val error: Int)

data class FeetechTelemetry(
    val servoId: Int,
    val velocity: Int,
    val load: Int,
    val voltage: Double,
    val temperatureC: Int,
)

class FeetechBus(
    private val port: FeetechPort,
    private val timeoutMs: Int = 100,
) {
    fun ping(servoId: Int): FeetechPingResult? {
        val request = FeetechProtocol.buildPacket(servoId, FeetechProtocol.INST_PING)
        val response = transact(request, servoId) ?: return null
        return FeetechPingResult(
            modelNumber = response.parameters.takeIf { it.size >= 2 }?.let(::littleEndianValue) ?: 0,
            error = response.error,
        )
    }

    fun scanIds(first: Int = 1, last: Int = FeetechProtocol.MAX_ID): List<Int> =
        (first.coerceAtLeast(1)..last.coerceAtMost(FeetechProtocol.MAX_ID)).filter { ping(it) != null }

    fun writeSyncVelocity(ids: List<Int>, values: List<Int>): Int {
        val encoded = values.map { FeetechProtocol.encodeSignMagnitude(it) }
        val packet = FeetechProtocol.buildSyncWrite(ids, FeetechProtocol.GOAL_VELOCITY, 2, encoded)
        port.flush()
        return port.write(packet, timeoutMs)
    }

    fun writeSyncTorqueEnable(ids: List<Int>, enabled: Boolean): Int =
        writeSyncUnsigned(ids, FeetechProtocol.TORQUE_ENABLE, 1, List(ids.size) { if (enabled) 1 else 0 })

    fun writeSyncUnsigned(ids: List<Int>, address: Int, valueLength: Int, values: List<Int>): Int {
        val packet = FeetechProtocol.buildSyncWrite(ids, address, valueLength, values)
        port.flush()
        return port.write(packet, timeoutMs)
    }

    /** Configure speed mode and torque limit while the group is disarmed. */
    fun configureVelocityMode(ids: List<Int>, torqueLimit: Int): Int {
        require(torqueLimit in 0..1023) { "torque limit out of range" }
        var bytes = 0
        bytes += writeSyncUnsigned(ids, FeetechProtocol.OPERATING_MODE, 1, List(ids.size) { 1 })
        bytes += writeSyncUnsigned(ids, FeetechProtocol.TORQUE_LIMIT, 2, List(ids.size) { torqueLimit })
        return bytes
    }

    fun readTelemetry(servoId: Int): FeetechTelemetry? {
        // PRESENT_VELOCITY..PRESENT_TEMPERATURE are contiguous on STS3215:
        // velocity (2), load (2), voltage (1), temperature (1).
        val response = readRegister(servoId, FeetechProtocol.PRESENT_VELOCITY, 6) ?: return null
        if (response.parameters.size < 6) return null
        val velocity = FeetechProtocol.decodeSignMagnitude(littleEndianValue(response.parameters.copyOfRange(0, 2)))
        val load = FeetechProtocol.decodeSignMagnitude(
            littleEndianValue(response.parameters.copyOfRange(2, 4)),
            signBit = 10,
        )
        val voltage = (response.parameters[4].toInt() and 0xFF) / 10.0
        val temperature = response.parameters[5].toInt() and 0xFF
        return FeetechTelemetry(servoId, velocity, load, voltage, temperature)
    }

    fun readRegister(servoId: Int, address: Int, length: Int): FeetechStatusPacket? {
        require(address in 0..255 && length in 1..251) { "invalid register read" }
        return transact(
            FeetechProtocol.buildPacket(
                servoId,
                FeetechProtocol.INST_READ,
                byteArrayOf(address.toByte(), length.toByte()),
            ),
            servoId,
        )
    }

    private fun transact(request: ByteArray, expectedId: Int): FeetechStatusPacket? {
        port.flush()
        port.write(request, timeoutMs)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var buffer = ByteArray(0)
        while (System.nanoTime() < deadline) {
            val chunk = port.read(64, timeoutMs)
            if (chunk.isNotEmpty()) buffer += chunk
            val response = FeetechProtocol.parseStatus(buffer)
            if (response != null && (response.servoId == expectedId || response.servoId == FeetechProtocol.BROADCAST_ID)) {
                return response
            }
        }
        return null
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        copyInto(result)
        other.copyInto(result, size)
        return result
    }
}
