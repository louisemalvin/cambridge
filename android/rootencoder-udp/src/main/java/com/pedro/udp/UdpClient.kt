/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pedro.udp

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.socket.base.UdpType
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.validMessage
import com.pedro.udp.utils.UdpSocket
import java.net.URISyntaxException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UdpClient(private val connectChecker: ConnectChecker) {
    private val commandManager = CommandManager()
    private val udpSender = UdpSender(connectChecker, commandManager)
    private var socket: UdpSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var scopeRetry = CoroutineScope(Dispatchers.IO)
    private var jobRetry: Job? = null

    @Volatile
    var isStreaming = false
        private set
    private var url: String? = null
    private var doingRetry = false
    private var numRetry = 0
    private var retries = 0

    val droppedAudioFrames: Long get() = udpSender.getDroppedAudioFrames()
    val droppedVideoFrames: Long get() = udpSender.getDroppedVideoFrames()
    val cacheSize: Int get() = udpSender.getCacheSize()
    val sentAudioFrames: Long get() = udpSender.getSentAudioFrames()
    val sentVideoFrames: Long get() = udpSender.getSentVideoFrames()
    val bytesSend: Long get() = udpSender.getBytesSend()
    var socketType = SocketType.JAVA
    var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

    fun setVideoCodec(videoCodec: VideoCodec) {
        if (!isStreaming) {
            commandManager.videoCodec = when (videoCodec) {
                VideoCodec.AV1 -> throw IllegalArgumentException(
                    "Unsupported codec: ${videoCodec.name}",
                )
                else -> videoCodec
            }
        }
    }

    fun setAudioCodec(audioCodec: AudioCodec) {
        if (!isStreaming) {
            commandManager.audioCodec = when (audioCodec) {
                AudioCodec.G711 -> throw IllegalArgumentException(
                    "Unsupported codec: ${audioCodec.name}",
                )
                else -> audioCodec
            }
        }
    }

    fun setDelay(millis: Long) = udpSender.setDelay(millis)

    fun setOnlyAudio(onlyAudio: Boolean) {
        commandManager.audioDisabled = false
        commandManager.videoDisabled = onlyAudio
    }

    fun setOnlyVideo(onlyVideo: Boolean) {
        commandManager.videoDisabled = false
        commandManager.audioDisabled = onlyVideo
    }

    fun setReTries(reTries: Int) {
        numRetry = reTries
        retries = reTries
    }

    fun shouldRetry(reason: String): Boolean =
        doingRetry && !reason.contains("Endpoint malformed") && retries > 0

    @JvmOverloads
    fun connect(url: String?, isRetry: Boolean = false) {
        if (!isRetry) doingRetry = true
        if (isStreaming && !isRetry) return
        isStreaming = true
        job = scope.launch {
            if (url == null) {
                failConnection("Endpoint malformed, should be: udp://ip:port")
                return@launch
            }
            this@UdpClient.url = url
            onMainThread { connectChecker.onConnectionStarted(url) }
            val parser = try {
                UrlParser.parse(url, arrayOf("udp"))
            } catch (_: URISyntaxException) {
                failConnection("Endpoint malformed, should be: udp://ip:port")
                return@launch
            }
            val port = parser.port
            if (port == null) {
                failConnection("Endpoint malformed, port is required")
                return@launch
            }
            commandManager.host = parser.host
            val error = runCatching {
                socket = UdpSocket(
                    socketType,
                    parser.host,
                    UdpType.getTypeByHost(parser.host),
                    port,
                    socketTimeout,
                )
                socket?.connect()
                udpSender.socket = socket
                udpSender.start()
                onMainThread { connectChecker.onConnectionSuccess() }
            }.exceptionOrNull()
            if (error != null) {
                Log.e(TAG, "connection error", error)
                failConnection("Error configure stream, ${error.validMessage()}")
            }
        }
    }

    private suspend fun failConnection(reason: String) {
        isStreaming = false
        onMainThread { connectChecker.onConnectionFailed(reason) }
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch { disconnect(clear = true) }
    }

    private suspend fun disconnect(clear: Boolean) {
        if (isStreaming) udpSender.stop(clear)
        socket?.close()
        if (clear) {
            retries = numRetry
            doingRetry = false
            isStreaming = false
            onMainThread { connectChecker.onDisconnect() }
            jobRetry?.cancelAndJoin()
            jobRetry = null
            scopeRetry.cancel()
            scopeRetry = CoroutineScope(Dispatchers.IO)
        }
        commandManager.reset()
        job?.cancelAndJoin()
        job = null
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO)
    }

    fun reConnect(delay: Long) = reConnect(delay, null)

    fun reConnect(delay: Long, backupUrl: String?) {
        jobRetry = scopeRetry.launch {
            retries--
            disconnect(clear = false)
            delay(delay)
            connect(backupUrl ?: url, isRetry = true)
        }
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) =
        udpSender.setAudioInfo(sampleRate, isStereo)

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) =
        udpSender.setVideoInfo(sps, pps, vps)

    fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandManager.videoDisabled) {
            udpSender.sendMediaFrame(
                MediaFrame(videoBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.VIDEO),
            )
        }
    }

    fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandManager.audioDisabled) {
            udpSender.sendMediaFrame(
                MediaFrame(audioBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.AUDIO),
            )
        }
    }

    @JvmOverloads
    fun hasCongestion(percentUsed: Float = 20f): Boolean = udpSender.hasCongestion(percentUsed)

    fun resetSentAudioFrames() = udpSender.resetSentAudioFrames()
    fun resetSentVideoFrames() = udpSender.resetSentVideoFrames()
    fun resetDroppedAudioFrames() = udpSender.resetDroppedAudioFrames()
    fun resetDroppedVideoFrames() = udpSender.resetDroppedVideoFrames()
    fun resetBytesSend() = udpSender.resetBytesSend()
    fun resizeCache(newSize: Int) = udpSender.resizeCache(newSize)
    fun setLogs(enable: Boolean) = udpSender.setLogs(enable)
    fun clearCache() = udpSender.clearCache()
    fun getItemsInCache(): Int = udpSender.getItemsInCache()
    fun setBitrateExponentialFactor(factor: Float) =
        udpSender.setBitrateExponentialFactor(factor)
    fun getBitrateExponentialFactor(): Float = udpSender.getBitrateExponentialFactor()

    private companion object {
        const val TAG = "UdpClient"
    }
}
