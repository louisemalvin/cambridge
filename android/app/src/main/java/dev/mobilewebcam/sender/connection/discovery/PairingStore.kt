package dev.mobilewebcam.sender.connection.discovery

import android.content.Context
import dev.mobilewebcam.sender.connection.control.http.ProtocolJson
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class PairingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var state: StoredPairings = load()

    val senderId: String
        @Synchronized get() = state.senderId

    @Synchronized
    fun hasApprovedReceivers(): Boolean = state.receivers.isNotEmpty()

    @Synchronized
    fun authentication(
        receiverId: String,
        token: String?,
        peerAddress: String,
    ): PairingAuthentication {
        val pairing = state.receivers[receiverId] ?: return PairingAuthentication.Unpaired
        if (token != null && pairing.peerAddress == peerAddress && constantTimeEquals(token, pairing.token)) {
            return PairingAuthentication.Authenticated(pairing.token)
        }
        return if (!pairing.tokenDelivered && token == null && pairing.peerAddress == peerAddress) {
            PairingAuthentication.PendingTokenDelivery(pairing.token)
        } else {
            PairingAuthentication.Unpaired
        }
    }

    @Synchronized
    fun approve(receiverId: String, receiverName: String, peerAddress: String) {
        val current = state.receivers[receiverId]
        val pairing = StoredReceiver(
            receiverName = receiverName,
            token = current?.token ?: UUID.randomUUID().toString(),
            peerAddress = peerAddress,
            tokenDelivered = false,
        )
        state = state.copy(receivers = state.receivers + (receiverId to pairing))
        persist()
    }

    @Synchronized
    fun markTokenDelivered(receiverId: String) {
        val pairing = state.receivers[receiverId] ?: return
        state = state.copy(
            receivers = state.receivers +
                (receiverId to pairing.copy(tokenDelivered = true)),
        )
        persist()
    }

    @Synchronized
    fun forget(receiverId: String) {
        if (receiverId !in state.receivers) return
        state = state.copy(receivers = state.receivers - receiverId)
        persist()
    }

    @Synchronized
    fun forgetAll() {
        if (state.receivers.isEmpty()) return
        state = state.copy(receivers = emptyMap())
        persist()
    }

    private fun load(): StoredPairings {
        val encoded = preferences.getString(STORAGE_KEY, null)
        if (encoded != null) {
            runCatching {
                return ProtocolJson.instance.decodeFromString<StoredPairings>(encoded)
            }
        }
        return StoredPairings(senderId = UUID.randomUUID().toString()).also(::persist)
    }

    private fun persist(value: StoredPairings = state) {
        preferences.edit()
            .putString(STORAGE_KEY, ProtocolJson.instance.encodeToString(value))
            .commit()
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        var difference = leftBytes.size xor rightBytes.size
        for (index in leftBytes.indices) {
            val leftByte = leftBytes[index]
            val rightByte = rightBytes.getOrElse(index) { ZERO_BYTE_VALUE }
            difference = difference or (leftByte.toInt() xor rightByte.toInt())
        }
        return difference == ZERO_DIFFERENCE
    }

    private companion object {
        const val ZERO_BYTE_VALUE: Byte = 0
        const val ZERO_DIFFERENCE = 0
        const val PREFERENCES_NAME = "sender-pairings"
        const val STORAGE_KEY = "pairings-v1"
    }
}

sealed interface PairingAuthentication {
    data class Authenticated(val token: String) : PairingAuthentication
    data class PendingTokenDelivery(val token: String) : PairingAuthentication
    data object Unpaired : PairingAuthentication
}

@Serializable
private data class StoredPairings(
    val senderId: String,
    val receivers: Map<String, StoredReceiver> = emptyMap(),
)

@Serializable
private data class StoredReceiver(
    val receiverName: String,
    val token: String,
    val peerAddress: String,
    val tokenDelivered: Boolean,
)
