package com.woozie.balancingrobot.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProtocolTest {
    @Test
    fun acceptsLot0SmokeCommand() {
        val result = WebProtocol.parseCommand("{\"v\":1,\"id\":\"abc\",\"type\":\"smoke\",\"payload\":{}}")

        assertTrue(result is ParseResult.Accepted)
        assertEquals("abc", (result as ParseResult.Accepted).command.id)
    }

    @Test
    fun rejectsMalformedJson() {
        val result = WebProtocol.parseCommand("not-json")

        assertEquals("INVALID_JSON", (result as ParseResult.Rejected).code)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val result = WebProtocol.parseCommand("{\"v\":2,\"id\":\"abc\",\"type\":\"smoke\"}")

        assertEquals("UNSUPPORTED_VERSION", (result as ParseResult.Rejected).code)
    }

    @Test
    fun rejectsUnknownCommand() {
        val result = WebProtocol.parseCommand("{\"v\":1,\"id\":\"abc\",\"type\":\"motor\"}")

        assertEquals("UNKNOWN_COMMAND", (result as ParseResult.Rejected).code)
    }

    @Test
    fun acceptsDiagnosticAndLogCommands() {
        assertTrue(WebProtocol.parseCommand("{\"v\":1,\"id\":\"d\",\"type\":\"diagnostics\"}") is ParseResult.Accepted)
        assertTrue(WebProtocol.parseCommand("{\"v\":1,\"id\":\"l\",\"type\":\"export_log\"}") is ParseResult.Accepted)
    }

    @Test
    fun acceptsExplicitManualMotorCommands() {
        val result = WebProtocol.parseCommand(
            "{\"v\":1,\"id\":\"m\",\"type\":\"manual_command\",\"payload\":{" +
                "\"value\":500,\"held\":true}}",
        ) as ParseResult.Accepted

        assertEquals(500, WebProtocol.payloadInt(result.command, "value"))
        assertTrue(WebProtocol.payloadBoolean(result.command, "held") == true)
    }

    @Test
    fun acceptsDedicatedSpeedTargetCommand() {
        val result = WebProtocol.parseCommand(
            "{\"v\":1,\"id\":\"s\",\"type\":\"set_speed_target\",\"payload\":{" +
                "\"speedTargetCmPerSec\":4.5}}",
        ) as ParseResult.Accepted

        assertEquals(4.5, WebProtocol.payloadDouble(result.command, "speedTargetCmPerSec")!!, 1e-9)
    }

    @Test
    fun createsDiagnosticFrame() {
        val frame = WebProtocol.diagnostics("d", "{\"gyroRateHz\":200}")

        assertTrue(frame.contains("\"type\":\"diagnostics\""))
        assertTrue(frame.contains("\"gyroRateHz\":200"))
    }

    @Test
    fun createsAckWithOptionalError() {
        val ack = WebProtocol.ack("abc", ok = false, stateVersion = 3, error = "OFF")

        assertTrue(ack.contains("\"id\":\"abc\""))
        assertTrue(ack.contains("\"stateVersion\":3"))
        assertTrue(ack.contains("\"error\":\"OFF\""))
    }

    @Test
    fun createsProtocolError() {
        val error = WebProtocol.error("INVALID_JSON", "JSON invalide")

        assertTrue(error.contains("\"type\":\"error\""))
        assertTrue(error.contains("\"code\":\"INVALID_JSON\""))
    }
}
