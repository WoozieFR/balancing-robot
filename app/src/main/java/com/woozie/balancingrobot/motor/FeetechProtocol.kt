package com.woozie.balancingrobot.motor

data class FeetechStatusPacket(
    val servoId: Int,
    val error: Int,
    val parameters: ByteArray,
)

object FeetechProtocol {
    const val BROADCAST_ID = 0xFE
    const val MAX_ID = 0xFC
    const val INST_PING = 0x01
    const val INST_READ = 0x02
    const val INST_WRITE = 0x03
    const val INST_SYNC_WRITE = 0x83

    const val OPERATING_MODE = 33
    const val MODE_VELOCITY = 1
    const val MODE_PWM = 2
    const val TORQUE_ENABLE = 40
    /** In STS3215 PWM mode this is the signed PWM duty command (0..1000). */
    const val GOAL_PWM = 44
    const val GOAL_VELOCITY = 46
    const val PWM_SIGN_BIT = 10
    const val PWM_MAX = (1 shl PWM_SIGN_BIT) - 1
    const val TORQUE_LIMIT = 48
    const val PRESENT_POSITION = 56
    const val PRESENT_VELOCITY = 58
    const val PRESENT_LOAD = 60
    const val PRESENT_VOLTAGE = 62
    const val PRESENT_TEMPERATURE = 63
    const val STATUS = 65
    const val MOVING = 66

    fun buildPacket(servoId: Int, instruction: Int, parameters: ByteArray = byteArrayOf()): ByteArray {
        require(servoId in 0..BROADCAST_ID) { "servo ID out of range" }
        require(instruction in 0..0xFF) { "instruction out of range" }
        require(parameters.size <= 251) { "packet is too large" }
        val length = parameters.size + 2
        val body = byteArrayOf(servoId.toByte(), length.toByte(), instruction.toByte()) + parameters
        val checksum = body.fold(0) { sum, value -> (sum + (value.toInt() and 0xFF)) and 0xFF }
        return byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + body + byteArrayOf((checksum.inv() and 0xFF).toByte())
    }

    fun parseStatus(data: ByteArray): FeetechStatusPacket? {
        val start = (0 until data.size - 1).firstOrNull {
            data[it].toInt() and 0xFF == 0xFF && data[it + 1].toInt() and 0xFF == 0xFF
        } ?: return null
        if (data.size - start < 6) return null
        val servoId = data[start + 2].toInt() and 0xFF
        val length = data[start + 3].toInt() and 0xFF
        val total = length + 4
        if (length < 2 || data.size - start < total) return null
        val checksumIndex = start + total - 1
        var sum = 0
        for (index in start + 2 until checksumIndex) sum = (sum + (data[index].toInt() and 0xFF)) and 0xFF
        if ((sum.inv() and 0xFF) != (data[checksumIndex].toInt() and 0xFF)) return null
        val error = data[start + 4].toInt() and 0xFF
        return FeetechStatusPacket(servoId, error, data.copyOfRange(start + 5, checksumIndex))
    }

    fun encodeSignMagnitude(value: Int, signBit: Int = 15): Int {
        require(signBit in 1..15) { "unsupported sign bit" }
        val magnitudeLimit = (1 shl signBit) - 1
        require(value in -magnitudeLimit..magnitudeLimit) { "signed value out of range" }
        return if (value < 0) -value or (1 shl signBit) else value
    }

    fun decodeSignMagnitude(value: Int, signBit: Int = 15): Int {
        val mask = (1 shl signBit) - 1
        return if ((value and (1 shl signBit)) != 0) -(value and mask) else value and mask
    }

    fun buildSyncWrite(
        ids: List<Int>,
        address: Int,
        valueLength: Int,
        values: List<Int>,
    ): ByteArray {
        require(ids.isNotEmpty() && ids.size == values.size) { "IDs and values must match" }
        require(ids.all { it in 0..MAX_ID }) { "invalid servo ID" }
        require(address in 0..255 && valueLength in 1..4) { "invalid register" }
        val parameters = ArrayList<Byte>(2 + ids.size * (valueLength + 1))
        parameters += address.toByte()
        parameters += valueLength.toByte()
        ids.zip(values).forEach { (id, value) ->
            val maxValue = (1L shl (valueLength * 8)) - 1L
            require(value.toLong() in 0L..maxValue) { "value out of range" }
            parameters += id.toByte()
            repeat(valueLength) { shift -> parameters += (value ushr (shift * 8)).toByte() }
        }
        return buildPacket(BROADCAST_ID, INST_SYNC_WRITE, parameters.toByteArray())
    }
}

fun littleEndianValue(bytes: ByteArray): Int = bytes.foldIndexed(0) { index, result, byte ->
    result or ((byte.toInt() and 0xFF) shl (index * 8))
}
