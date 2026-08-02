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

import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.udp.utils.UdpSocket
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CommandManager {
    var MTU = MPEG_TS_UDP_PAYLOAD_BYTES
    var audioDisabled = false
    var videoDisabled = false
    var host = ""
    private val writeSync = Mutex(locked = false)
    var videoCodec = VideoCodec.H264
    var audioCodec = AudioCodec.AAC

    @Throws(IOException::class)
    suspend fun writeData(packet: MpegTsPacket, socket: UdpSocket?): Int {
        writeSync.withLock {
            return socket?.write(packet) ?: 0
        }
    }

    fun reset() {
        MTU = MPEG_TS_UDP_PAYLOAD_BYTES
        host = ""
    }
}
