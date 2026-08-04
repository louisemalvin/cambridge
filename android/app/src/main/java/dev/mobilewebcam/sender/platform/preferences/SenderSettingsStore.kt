package dev.mobilewebcam.sender.platform.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SenderSettingsStore(
    context: Context,
) : SenderSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val tokenCipher = ReceiverTokenCipher()
    private val settingsFlow = MutableStateFlow(load())

    override val state: StateFlow<SenderSettings> = settingsFlow.asStateFlow()

    @Synchronized
    override fun updateCodecPreference(preference: CodecPreference) {
        persist(settingsFlow.value.copy(codecPreference = preference))
    }

    @Synchronized
    override fun updateProfile(profile: VideoProfile) {
        persist(settingsFlow.value.copy(profile = profile))
    }

    @Synchronized
    override fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) {
        persist(settingsFlow.value.copy(receiverEndpoint = endpoint))
    }

    private fun load(): SenderSettings {
        val codecPreference = preferences.getString(CODEC_PREFERENCE_KEY, null)
            ?.let { stored -> CodecPreference.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_CODEC_PREFERENCE
        val profile = preferences.getString(PROFILE_KEY, null)
            ?.let { stored -> VideoProfiles.all.firstOrNull { it.id == stored } }
            ?: VideoProfiles.default
        val receiverEndpoint = loadReceiverEndpoint()
        return SenderSettings(
            codecPreference = codecPreference,
            profile = profile,
            receiverEndpoint = receiverEndpoint,
        )
    }

    private fun persist(settings: SenderSettings) {
        val editor = preferences.edit()
            .putString(CODEC_PREFERENCE_KEY, settings.codecPreference.name)
            .putString(PROFILE_KEY, settings.profile.id)
        val endpoint = settings.receiverEndpoint
        if (endpoint == null) {
            editor.remove(RECEIVER_HOST_KEY)
                .remove(RECEIVER_PORT_KEY)
                .remove(RECEIVER_NAME_KEY)
                .remove(RECEIVER_ID_KEY)
                .remove(RECEIVER_AUTH_REQUIRED_KEY)
                .remove(RECEIVER_TOKEN_KEY)
        } else {
            editor.putString(RECEIVER_HOST_KEY, endpoint.host)
                .putInt(RECEIVER_PORT_KEY, endpoint.controlPort)
                .putString(RECEIVER_NAME_KEY, endpoint.displayName)
            endpoint.receiverId?.let { receiverId ->
                editor.putString(RECEIVER_ID_KEY, receiverId)
            } ?: editor.remove(RECEIVER_ID_KEY)
            editor.putBoolean(RECEIVER_AUTH_REQUIRED_KEY, endpoint.authenticationRequired)
            endpoint.controlToken?.takeIf(String::isNotBlank)?.let { token ->
                editor.putString(RECEIVER_TOKEN_KEY, tokenCipher.encrypt(token))
            } ?: editor.remove(RECEIVER_TOKEN_KEY)
        }
        editor.commit()
        settingsFlow.value = settings
    }

    private fun loadReceiverEndpoint(): ReceiverEndpoint? {
        val host = preferences.getString(RECEIVER_HOST_KEY, null) ?: return null
        val port = preferences.getInt(RECEIVER_PORT_KEY, INVALID_PORT)
        val displayName = preferences.getString(RECEIVER_NAME_KEY, null)
            ?: DEFAULT_RECEIVER_NAME
        val encryptedToken = preferences.getString(RECEIVER_TOKEN_KEY, null)
        val token = encryptedToken?.let { value -> runCatching { tokenCipher.decrypt(value) }.getOrNull() }
        val receiverId = preferences.getString(RECEIVER_ID_KEY, null)
        val authenticationRequired = preferences.getBoolean(RECEIVER_AUTH_REQUIRED_KEY, false)
        return ReceiverEndpoint(
            host = host,
            controlPort = port,
            displayName = displayName,
            controlToken = token,
            receiverId = receiverId,
            authenticationRequired = authenticationRequired,
        ).takeIf(ReceiverEndpoint::isValid)
    }

    private companion object {
        const val PREFERENCES_NAME = "sender-settings"
        const val CODEC_PREFERENCE_KEY = "codec-preference"
        const val PROFILE_KEY = "profile"
        const val RECEIVER_HOST_KEY = "receiver-host"
        const val RECEIVER_PORT_KEY = "receiver-port"
        const val RECEIVER_NAME_KEY = "receiver-name"
        const val RECEIVER_ID_KEY = "receiver-id"
        const val RECEIVER_AUTH_REQUIRED_KEY = "receiver-auth-required"
        const val RECEIVER_TOKEN_KEY = "receiver-token"
        const val INVALID_PORT = -1
        const val DEFAULT_RECEIVER_NAME = "Receiver"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "mobile-webcam-receiver-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        val DEFAULT_CODEC_PREFERENCE = CodecPreference.AUTO_PREFER_H265
    }

    private inner class ReceiverTokenCipher {
        private val random = SecureRandom()

        fun encrypt(token: String): String {
            val cipher = cipher(Cipher.ENCRYPT_MODE)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(token.encodeToByteArray())
            return Base64.encodeToString(
                ByteBuffer.allocate(iv.size + ciphertext.size)
                    .put(iv)
                    .put(ciphertext)
                    .array(),
                Base64.NO_WRAP,
            )
        }

        fun decrypt(encoded: String): String {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(encrypted)
            val iv = ByteArray(GCM_IV_BYTES).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = cipher(Cipher.DECRYPT_MODE, iv)
            return cipher.doFinal(ciphertext).decodeToString()
        }

        private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val initializationVector = iv ?: ByteArray(GCM_IV_BYTES).also(random::nextBytes)
            cipher.init(mode, key(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector))
            return cipher
        }

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return keyStore.getKey(KEY_ALIAS, null) as SecretKey
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
        }
    }

}
