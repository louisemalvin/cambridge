package dev.cambridge.sender.media.streaming.cambridge

import android.content.Context

class GStreamerTransport(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    data class Config(
        val remoteHost: String,
        val remoteRtpPort: Int,
        val remoteRtcpPort: Int,
        val localRtcpPort: Int,
        val targetBitrateBps: Int,
        val mtuBytes: Int,
    )

    interface Listener {
        fun onEstimatedBitrateChanged(bitrateBps: Int)
        fun onKeyframeRequested()
        fun onTransportError(message: String)
    }

    private val lock = Any()

    private var nativeHandle: Long = 0

    init {
        GStreamerRuntime.initialize(context)
        synchronized(lock) {
            nativeHandle = nativeCreate()
            check(nativeHandle != 0L) { "CamBridge GStreamer native transport could not be created" }
        }
    }

    fun start(config: Config) {
        synchronized(lock) {
            check(nativeHandle != 0L) { "CamBridge GStreamer transport is closed" }
            check(nativeStart(
                nativeHandle,
                config.remoteHost,
                config.remoteRtpPort,
                config.remoteRtcpPort,
                config.localRtcpPort,
                config.targetBitrateBps,
                config.mtuBytes,
            )) { "CamBridge GStreamer transport failed to start" }
        }
    }

    fun pushAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
    ): Boolean {
        val handle = synchronized(lock) { nativeHandle }
        if (handle == 0L) return false
        return nativePushAccessUnit(handle, bytes, presentationTimeUs, keyFrame)
    }

    fun stop() {
        val handle = synchronized(lock) { nativeHandle }
        if (handle != 0L) {
            nativeStop(handle)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (nativeHandle != 0L) {
                nativeStop(nativeHandle)
                nativeDestroy(nativeHandle)
                nativeHandle = 0
            }
        }
    }

    @Suppress("unused")
    private fun onNativeEstimatedBitrateChanged(bitrateBps: Int) {
        listener.onEstimatedBitrateChanged(bitrateBps)
    }

    @Suppress("unused")
    private fun onNativeKeyframeRequested() {
        listener.onKeyframeRequested()
    }

    @Suppress("unused")
    private fun onNativeTransportError(message: String) {
        listener.onTransportError(message)
    }

    private external fun nativeCreate(): Long

    private external fun nativeStart(
        handle: Long,
        remoteHost: String,
        remoteRtpPort: Int,
        remoteRtcpPort: Int,
        localRtcpPort: Int,
        targetBitrateBps: Int,
        mtuBytes: Int,
    ): Boolean

    private external fun nativePushAccessUnit(
        handle: Long,
        bytes: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
    ): Boolean

    private external fun nativeStop(handle: Long)

    private external fun nativeDestroy(handle: Long)
}
