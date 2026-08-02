package dev.mobilewebcam.sender.discovery

import android.util.Log
import dev.mobilewebcam.sender.control.http.ProtocolJson
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SenderControlServer(
    private val coordinator: SenderConnectionCoordinator,
    private val senderId: String,
    private val displayName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        check(serverSocket == null) { "Sender control server is already running" }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(SENDER_CONTROL_PORT))
        }
        serverSocket = server
        acceptJob = scope.launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrElse { error ->
                    if (isActive) Log.e(TAG, "Sender control accept failed", error)
                    break
                }
                launch { handle(socket) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MILLIS
            val peerAddress = client.inetAddress.hostAddress.orEmpty()
            val encoded = runCatching {
                val requestJson = readLine(client)
                val action = ProtocolJson.instance.parseToJsonElement(requestJson)
                    .jsonObject["action"]?.jsonPrimitive?.contentOrNull
                if (action == "describe") {
                    val request = ProtocolJson.instance.decodeFromString<DescribeSenderRequestDto>(
                        requestJson,
                    )
                    require(request.protocolVersion == SENDER_CONTROL_PROTOCOL_VERSION)
                    ProtocolJson.instance.encodeToString(
                        SenderAdvertisementDto(
                            protocolVersion = SENDER_CONTROL_PROTOCOL_VERSION,
                            senderId = senderId,
                            displayName = displayName,
                            controlPort = SENDER_CONTROL_PORT,
                        ),
                    )
                } else {
                    val request = ProtocolJson.instance.decodeFromString<StartStreamRequestDto>(
                        requestJson,
                    )
                    val response = coordinator.handleStartRequest(request, peerAddress).also {
                        Log.i(
                            TAG,
                            "Control request from ${request.receiverName}: ${it.status}",
                        )
                    }
                    ProtocolJson.instance.encodeToString(response)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Invalid sender control request", error)
                ProtocolJson.instance.encodeToString(
                    StartStreamResponseDto(
                        protocolVersion = SENDER_CONTROL_PROTOCOL_VERSION,
                        senderId = senderId,
                        status = StartStreamStatusDto.INVALID_REQUEST,
                        message = error.message,
                    ),
                )
            }
            client.getOutputStream().write((encoded + "\n").encodeToByteArray())
            client.getOutputStream().flush()
        }
    }

    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_MESSAGE_BYTES) {
            val byte = input.read()
            if (byte == -1 || byte == '\n'.code) break
            output.write(byte)
        }
        require(output.size() in MIN_MESSAGE_BYTES until MAX_MESSAGE_BYTES) {
            "Sender control request is empty or too large"
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TAG = "MobileWebcamControl"
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val MIN_MESSAGE_BYTES = 1
        const val MAX_MESSAGE_BYTES = 16 * 1024
    }
}
