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

import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.base.BaseSender
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.validMessage
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.mpeg2ts.MpegType
import com.pedro.srt.mpeg2ts.packets.AacPacket
import com.pedro.srt.mpeg2ts.packets.BasePacket
import com.pedro.srt.mpeg2ts.packets.H26XPacket
import com.pedro.srt.mpeg2ts.packets.OpusPacket
import com.pedro.srt.mpeg2ts.psi.Psi
import com.pedro.srt.mpeg2ts.psi.PsiManager
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.data.PacketPosition
import com.pedro.srt.utils.chunkPackets
import com.pedro.srt.utils.toCodec
import com.pedro.udp.utils.UdpSocket
import java.nio.ByteBuffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

class UdpSender(
    connectChecker: ConnectChecker,
    private val commandManager: CommandManager,
) : BaseSender(connectChecker, "UdpSender") {
    private val service = Mpeg2TsService()
    private val psiManager = PsiManager(service).apply {
        upgradePatVersion()
        upgradeSdtVersion()
    }
    private val limitSize = MPEG_TS_UDP_PAYLOAD_BYTES
    private val mpegTsPacketizer = MpegTsPacketizer(psiManager)
    private var audioPacket: BasePacket = AacPacket(limitSize, psiManager)
    private val videoPacket = H26XPacket(limitSize, psiManager)
    var socket: UdpSocket? = null

    init {
        check(MpegTsPacketizer.packetSize == MPEG_TS_PACKET_BYTES)
        check(limitSize <= IPV6_MINIMUM_UDP_PAYLOAD_BYTES)
    }

    private fun setTrackConfig(videoEnabled: Boolean, audioEnabled: Boolean) {
        service.clear()
        if (audioEnabled) service.addTrack(commandManager.audioCodec.toCodec())
        if (videoEnabled) service.addTrack(commandManager.videoCodec.toCodec())
        service.generatePmt()
        psiManager.updateService(service)
    }

    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        videoPacket.setVideoCodec(commandManager.videoCodec.toCodec())
        videoPacket.sendVideoInfo(sps, pps, vps)
    }

    override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        audioPacket = when (commandManager.audioCodec) {
            AudioCodec.AAC -> AacPacket(limitSize, psiManager).apply {
                sendAudioInfo(sampleRate, isStereo)
            }
            AudioCodec.OPUS -> OpusPacket(limitSize, psiManager)
            AudioCodec.G711 -> throw IllegalArgumentException(
                "Unsupported codec: ${commandManager.audioCodec.name}",
            )
        }
    }

    override suspend fun onRun() {
        val chunkSize = limitSize / MpegTsPacketizer.packetSize
        audioPacket.setLimitSize(limitSize)
        videoPacket.setLimitSize(limitSize)
        setTrackConfig(!commandManager.videoDisabled, !commandManager.audioDisabled)

        val psiList = mutableListOf<Psi>(psiManager.getPat())
        psiManager.getPmt()?.let { psiList.add(it) }
        psiList.add(psiManager.getSdt())
        val psiPackets = mpegTsPacketizer.write(psiList).chunkPackets(chunkSize).map { bytes ->
            MpegTsPacket(bytes, MpegType.PSI, PacketPosition.SINGLE, isKey = false)
        }
        sendPackets(psiPackets, MpegType.PSI)

        while (scope.isActive && running) {
            val error = runCatching {
                val mediaFrame = runInterruptible { queue.take() }
                getMpegTsPackets(mediaFrame) { packets ->
                    val isKey = packets[0].isKey
                    val periodicPsi = psiManager.checkSendInfo(
                        isKey,
                        mpegTsPacketizer,
                        chunkSize,
                    )
                    val psiBytes = sendPackets(periodicPsi, MpegType.PSI)
                    val mediaBytes = sendPackets(packets, packets[0].type)
                    bytesSend.addAndGet(psiBytes + mediaBytes)
                    bytesSendPerSecond.addAndGet(psiBytes + mediaBytes)
                }
            }.exceptionOrNull()
            if (error != null) {
                onMainThread {
                    connectChecker.onConnectionFailed(
                        "Error send packet, ${error.validMessage()}",
                    )
                }
                Log.e(TAG, "send error", error)
                running = false
                return
            }
        }
    }

    override suspend fun stopImp(clear: Boolean) {
        psiManager.reset()
        service.clear()
        mpegTsPacketizer.reset()
        audioPacket.reset(clear)
        videoPacket.reset(clear)
    }

    private suspend fun sendPackets(packets: List<MpegTsPacket>, type: MpegType): Long {
        if (packets.isEmpty()) return 0
        var sentBytes = 0L
        packets.forEach { packet ->
            sentBytes += commandManager.writeData(packet, socket)
        }
        if (type == MpegType.VIDEO) videoFramesSent.incrementAndGet()
        if (type == MpegType.AUDIO) audioFramesSent.incrementAndGet()
        if (isEnableLogs) Log.i(TAG, "wrote ${type.name} packet, size $sentBytes")
        return sentBytes
    }

    private suspend fun getMpegTsPackets(
        mediaFrame: MediaFrame?,
        callback: suspend (List<MpegTsPacket>) -> Unit,
    ) {
        if (mediaFrame == null) return
        when (mediaFrame.type) {
            MediaFrame.Type.VIDEO -> videoPacket.createAndSendPacket(mediaFrame, callback)
            MediaFrame.Type.AUDIO -> audioPacket.createAndSendPacket(mediaFrame, callback)
        }
    }
}
