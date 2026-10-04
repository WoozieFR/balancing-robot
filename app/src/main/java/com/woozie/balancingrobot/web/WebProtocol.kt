package com.woozie.balancingrobot.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

data class WebCommandFrame(
    val id: String,
    val type: String,
    val payload: JsonObject,
)

sealed interface ParseResult {
    data class Accepted(val command: WebCommandFrame) : ParseResult
    data class Rejected(val code: String, val message: String) : ParseResult
}

object WebProtocol {
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun parseCommand(text: String): ParseResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { return ParseResult.Rejected("INVALID_JSON", "JSON invalide") }

        val version = root["v"]?.jsonPrimitive?.intOrNull
            ?: return ParseResult.Rejected("INVALID_VERSION", "Version absente")
        if (version != VERSION) {
            return ParseResult.Rejected("UNSUPPORTED_VERSION", "Version $version non supportée")
        }

        val id = root["id"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Rejected("INVALID_ID", "Identifiant absent")
        val type = root["type"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Rejected("INVALID_TYPE", "Type absent")
        if (type !in setOf(
                "smoke",
                "diagnostics",
                "export_log",
                "connect_usb",
                "disconnect_usb",
                "scan_bus",
                "configure_motors",
                "arm_manual",
                "disarm",
                "ack_fault",
                "set_speed_target",
                "update_parameters",
                "arm_balance",
                "disarm_balance",
                "start_recording",
                "stop_recording",
                "export_control_log",
                "manual_command",
                "step_sequence",
            )
        ) {
            return ParseResult.Rejected("UNKNOWN_COMMAND", "Commande $type inconnue")
        }

        val payloadElement = root["payload"]
        val payload = when {
            payloadElement == null -> buildJsonObject { }
            payloadElement is JsonObject -> payloadElement
            else -> return ParseResult.Rejected("INVALID_PAYLOAD", "Payload objet attendu")
        }
        return ParseResult.Accepted(WebCommandFrame(id, type, payload))
    }

    fun payloadInt(command: WebCommandFrame, key: String): Int? =
        command.payload[key]?.jsonPrimitive?.intOrNull

    fun payloadBoolean(command: WebCommandFrame, key: String): Boolean? =
        command.payload[key]?.jsonPrimitive?.booleanOrNull

    fun payloadDouble(command: WebCommandFrame, key: String): Double? =
        command.payload[key]?.jsonPrimitive?.doubleOrNull

    fun payloadString(command: WebCommandFrame, key: String): String? =
        command.payload[key]?.jsonPrimitive?.contentOrNull

    fun ack(
        id: String,
        ok: Boolean,
        stateVersion: Int = 0,
        error: String? = null,
        message: String? = null,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("type", "ack")
            put("id", id)
            put("ok", ok)
            put("stateVersion", stateVersion)
            error?.let { put("error", it) }
            message?.let { put("message", it) }
        }.toString()

    fun diagnostics(id: String, jsonPayload: String): String = buildJsonObject {
        put("v", VERSION)
        put("type", "diagnostics")
        put("id", id)
        put("payload", json.parseToJsonElement(jsonPayload))
    }.toString()

    fun error(code: String, message: String): String =
        buildJsonObject {
            put("v", VERSION)
            put("type", "error")
            put("code", code)
            put("message", message)
        }.toString()
}
