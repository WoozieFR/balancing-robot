package com.woozie.balancingrobot.web

import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small Android-safe local server used by the diagnostic and remote-control
 * page. It intentionally keeps the HTTP/WebSocket framing dependency-free.
 */
class SocketRobotWebServer(
    private val assets: AssetManager,
    private val scope: CoroutineScope,
    private val isServiceRunning: () -> Boolean,
    private val diagnosticsJson: () -> String = { "{}" },
    private val logCsv: () -> String = { "" },
    private val motorLogCsv: () -> String = { "" },
    private val controlLogCsv: () -> String = { "" },
    private val commandHandler: (WebCommandFrame) -> String? = { null },
    private val port: Int = 8766,
) {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var listenSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var boundPort: Int = 0
        private set

    @Synchronized
    fun start() {
        check(listenSocket == null) { "Serveur déjà démarré" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port))
        boundPort = port
        listenSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        listenSocket?.close()
        listenSocket = null
        boundPort = 0
        clients.toList().forEach { client -> runCatching { client.close() } }
        clients.clear()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (currentCoroutineContext().isActive) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) break
                continue
            }
            clients += client
            scope.launch(Dispatchers.IO) { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val requestLine = readAsciiLine(input) ?: return
                val headers = buildMap {
                    while (true) {
                        val line = readAsciiLine(input) ?: return@buildMap
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
                        }
                    }
                }
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeHttp(output, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray())
                    return
                }
                val path = parts[1].substringBefore('?')
                val isWebSocket = path == "/ws" &&
                    headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
                if (isWebSocket) {
                    handleWebSocket(input, output, headers["sec-websocket-key"])
                } else {
                    handleHttp(output, path)
                }
            } catch (_: SocketTimeoutException) {
                // An idle or incomplete HTTP client is discarded safely.
            } finally {
                clients.remove(client)
            }
        }
    }

    private fun handleHttp(output: BufferedOutputStream, path: String) {
        when (path) {
            "/health" -> {
                val body = "{\"ok\":true,\"serviceRunning\":${isServiceRunning()}}"
                    .toByteArray(Charsets.UTF_8)
                writeHttp(output, 200, "application/json; charset=utf-8", body)
            }
            "/diagnostics" -> writeHttp(
                output,
                200,
                "application/json; charset=utf-8",
                diagnosticsJson().toByteArray(Charsets.UTF_8),
            )
            "/log.csv" -> writeHttp(
                output,
                200,
                "text/csv; charset=utf-8",
                logCsv().toByteArray(Charsets.UTF_8),
            )
            "/motor-log.csv" -> writeHttp(
                output,
                200,
                "text/csv; charset=utf-8",
                motorLogCsv().toByteArray(Charsets.UTF_8),
            )
            "/control-log.csv" -> writeHttp(
                output,
                200,
                "text/csv; charset=utf-8",
                controlLogCsv().toByteArray(Charsets.UTF_8),
            )
            "/" -> writeAsset(output, "web/index.html", "text/html; charset=utf-8")
            "/app.js" -> writeAsset(output, "web/app.js", "text/javascript; charset=utf-8")
            "/styles.css" -> writeAsset(output, "web/styles.css", "text/css; charset=utf-8")
            else -> writeHttp(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
        }
    }

    private fun writeAsset(output: BufferedOutputStream, name: String, contentType: String) {
        val body = assets.open(name).use(InputStream::readBytes)
        writeHttp(output, 200, contentType, body)
    }

    private fun writeHttp(output: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        output.write(
            "HTTP/1.1 $status $reason\r\n".toByteArray(Charsets.ISO_8859_1),
        )
        output.write("Content-Type: $contentType\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write("Content-Length: ${body.size}\r\nCache-Control: no-store, max-age=0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun handleWebSocket(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        key: String?,
    ) {
        if (key.isNullOrBlank()) {
            writeHttp(output, 400, "text/plain; charset=utf-8", "Missing WebSocket key".toByteArray())
            return
        }
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        output.flush()
        writeWebSocketFrame(output, 0x1, "{\"v\":1,\"type\":\"hello\",\"lot\":3}".toByteArray())

        while (true) {
            val frame = readWebSocketFrame(input) ?: return
            when (frame.opcode) {
                0x8 -> return
                0x9 -> writeWebSocketFrame(output, 0xA, frame.payload)
                0x1 -> {
                    val response = when (val result = WebProtocol.parseCommand(frame.payload.toString(Charsets.UTF_8))) {
                        is ParseResult.Accepted -> when (result.command.type) {
                            "diagnostics" -> WebProtocol.diagnostics(result.command.id, diagnosticsJson())
                            "export_log" -> WebProtocol.ack(result.command.id, ok = true, message = "GET /log.csv")
                            "export_control_log" -> WebProtocol.ack(result.command.id, ok = true, message = "GET /control-log.csv")
                            else -> commandHandler(result.command)
                                ?: WebProtocol.ack(result.command.id, ok = true)
                        }
                        is ParseResult.Rejected -> WebProtocol.error(result.code, result.message)
                    }
                    writeWebSocketFrame(output, 0x1, response.toByteArray())
                }
            }
        }
    }

    private fun readWebSocketFrame(input: BufferedInputStream): WebSocketFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        var length = (second and 0x7F).toLong()
        when (length) {
            126L -> length = readUnsignedShort(input).toLong()
            127L -> length = readLong(input)
        }
        if (length < 0 || length > 1_048_576) return null
        val masked = (second and 0x80) != 0
        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, length.toInt())
        if (masked && mask != null) {
            payload.forEachIndexed { index, byte -> payload[index] = (byte.toInt() xor mask[index % 4].toInt()).toByte() }
        }
        return WebSocketFrame(first and 0x0F, payload)
    }

    private fun writeWebSocketFrame(output: BufferedOutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or (opcode and 0x0F))
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 65_535 -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size)
            }
            else -> {
                output.write(127)
                var value = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) output.write((value ushr shift).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readUnsignedShort(input: InputStream): Int =
        (readByte(input) shl 8) or readByte(input)

    private fun readLong(input: InputStream): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or readByte(input).toLong() }
        return value
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count < 0) throw SocketException("Connexion WebSocket interrompue")
            offset += count
        }
        return data
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw SocketException("Connexion interrompue")
        return value
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > 16_384) throw SocketException("Ligne HTTP trop longue")
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private data class WebSocketFrame(val opcode: Int, val payload: ByteArray)
}
