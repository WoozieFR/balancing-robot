package com.woozie.balancingrobot.web

import android.content.res.AssetManager
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.collect

class RobotWebServer(
    private val assets: AssetManager,
    private val isServiceRunning: () -> Boolean,
) {
    fun install(application: Application) {
        application.install(WebSockets)
        application.routing {
            get("/health") {
                call.respondText(
                    text = "{\"ok\":true,\"serviceRunning\":${isServiceRunning()}}",
                    contentType = ContentType.Application.Json,
                )
            }
            get("/") {
                call.respondText(
                    assets.open("web/index.html").bufferedReader().use { it.readText() },
                    ContentType.Text.Html,
                )
            }
            get("/app.js") {
                call.respondText(
                    assets.open("web/app.js").bufferedReader().use { it.readText() },
                    ContentType.parse("text/javascript"),
                )
            }
            webSocket("/ws") {
                send("{\"v\":1,\"type\":\"hello\",\"lot\":0}")
                incoming.consumeAsFlow().collect { frame ->
                    if (frame is Frame.Text) {
                        when (val result = WebProtocol.parseCommand(frame.readText())) {
                            is ParseResult.Accepted -> send(WebProtocol.ack(result.command.id, ok = true))
                            is ParseResult.Rejected -> send(WebProtocol.error(result.code, result.message))
                        }
                    }
                }
            }
        }
    }
}
