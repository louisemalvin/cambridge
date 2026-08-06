package dev.mobilewebcam.sender.connection.control.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

internal class DirectControlConnection private constructor(
    private val socket: Socket,
) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val outputLock = Any()

    suspend fun send(message: JsonObject) = withContext(Dispatchers.IO) {
        val encoded = Json.encodeToString(JsonObject.serializer(), message).encodeToByteArray()
        require(encoded.size <= DirectStreamContract.MAXIMUM_CONTROL_MESSAGE_BYTES) {
            "Control message exceeds the configured maximum"
        }
        synchronized(outputLock) {
            output.writeInt(encoded.size)
            output.write(encoded)
            output.flush()
        }
    }

    suspend fun receive(): JsonObject? = withContext(Dispatchers.IO) {
        val size = try {
            input.readInt()
        } catch (_: IOException) {
            return@withContext null
        }
        if (size <= EMPTY_MESSAGE_BYTES || size > DirectStreamContract.MAXIMUM_CONTROL_MESSAGE_BYTES) {
            error("Control frame exceeds the configured maximum")
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        Json.decodeFromString(JsonObject.serializer(), bytes.decodeToString())
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val EMPTY_MESSAGE_BYTES = 0
        private const val NO_READ_TIMEOUT_MILLIS = 0

        suspend fun connect(
            host: String,
            port: Int,
            readTimeoutMillis: Int = NO_READ_TIMEOUT_MILLIS,
        ): DirectControlConnection = withContext(Dispatchers.IO) {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = readTimeoutMillis
                connect(
                    InetSocketAddress(host, port),
                    DirectStreamContract.CONNECT_TIMEOUT_MILLIS,
                )
            }.let(::DirectControlConnection)
        }
    }
}
