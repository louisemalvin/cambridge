package dev.mobilewebcam.sender.media.streaming.direct

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.mobilewebcam.sender.connection.control.direct.DirectControlConnection
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.intField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.longField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.requireProtocolVersion
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringFieldOrNull
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.media.camera.PhysicalLensOption
import dev.mobilewebcam.sender.media.camera.SessionTransform
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import dev.mobilewebcam.sender.model.DirectStreamEndpoint
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DirectRtpStreamEngine(
    context: Context,
    private val logger: AppLogger = AndroidAppLogger,
) : StreamEngine, CameraController {
    private val applicationContext = context.applicationContext
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val camera = Camera2Capture(applicationContext, logger)
    private val lifecycleMutex = Mutex()
    private val stateFlow = MutableStateFlow<CameraInteractionState>(CameraInteractionState.inactive())
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encodedQueue = ArrayBlockingQueue<EncodedAccessUnit>(DirectStreamContract.MAXIMUM_ENCODED_QUEUE)
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var encoderSurface: Surface? = null
    private var configuration: StreamConfiguration? = null
    private var controlConnection: DirectControlConnection? = null
    private var udpSocket: DatagramSocket? = null
    private var senderJob: Job? = null
    private var controlReaderJob: Job? = null
    private var previewSurface: CameraPreviewSurface? = null
    @Volatile
    private var codecConfigAnnexB = ByteArray(EMPTY_BYTE_COUNT)
    private var streamEndpoint: DirectStreamEndpoint? = null
    private var nextSequence = AtomicInteger(EMPTY_BYTE_COUNT)
    private var ssrc = EMPTY_BYTE_COUNT
    private var prepared = false
    private var running = false
    private val encodedQueueDrops = AtomicLong(EMPTY_LONG_VALUE)
    private val maximumEncodedQueueOccupancy = AtomicInteger(EMPTY_BYTE_COUNT)
    private val encodedAccessUnits = AtomicLong(EMPTY_LONG_VALUE)
    private val encodedBytes = AtomicLong(EMPTY_LONG_VALUE)
    private val encodedKeyFrames = AtomicLong(EMPTY_LONG_VALUE)
    private val rtpPacketsSent = AtomicLong(EMPTY_LONG_VALUE)
    private val rtpBytesSent = AtomicLong(EMPTY_LONG_VALUE)
    private val udpSendErrors = AtomicLong(EMPTY_LONG_VALUE)
    private val maximumUdpSendDurationNs = AtomicLong(EMPTY_LONG_VALUE)
    private val lastSenderSummaryNs = AtomicLong(EMPTY_LONG_VALUE)

    override val events: Flow<StreamEngineEvent> = eventFlow
    override val state: StateFlow<CameraInteractionState> = stateFlow.asStateFlow()

    override suspend fun snapshotSessionTransform(
        codedWidth: Int,
        codedHeight: Int,
        orientation: StreamOrientation,
    ): SessionTransform =
        lifecycleMutex.withLock {
            camera.snapshotSessionTransform(codedWidth, codedHeight, orientation)
        }

    init {
        workerScope.launch {
            camera.cameraState.collect { state -> stateFlow.value = state }
        }
    }

    override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> = lifecycleMutex.withLock {
        try {
            check(!prepared) { "The direct sender is already prepared" }
            require(configuration.codec.protocolId == H264_CODEC) { "The direct sender supports H.264 only" }
            resetTelemetry()
            this.configuration = configuration
            prepareCodec(configuration)
            camera.prepare()
            prepared = true
            emit(
                "encoder_prepared",
                mapOf(
                    "codec" to H264_CODEC,
                    "encoder" to codec?.name,
                    "width" to configuration.profile.width,
                    "height" to configuration.profile.height,
                    "fps" to configuration.profile.fps,
                    "bitrateBps" to configuration.bitrateBps,
                    "bFrames" to NO_B_FRAMES,
                    "keyframeIntervalSeconds" to configuration.keyframeIntervalSeconds,
                ),
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            runCatching { camera.stop() }
            releaseCodec()
            this@DirectRtpStreamEngine.configuration = null
            prepared = false
            throw cancelled
        } catch (cause: Throwable) {
            runCatching { camera.stop() }
            releaseCodec()
            this@DirectRtpStreamEngine.configuration = null
            prepared = false
            Result.failure(cause)
        }
    }

    override suspend fun start(endpoint: DirectStreamEndpoint): Result<Unit> = lifecycleMutex.withLock {
        runCatching {
            check(prepared) { "The direct sender has not been prepared" }
            check(!running) { "The direct sender is already running" }
            val streamConfiguration = configuration ?: error("Stream configuration is unavailable")
            val sessionTransform = streamConfiguration.sessionTransform
                ?: error("The session contract must be resolved before streaming")
            require(sessionTransform.codedWidth == streamConfiguration.profile.width &&
                sessionTransform.codedHeight == streamConfiguration.profile.height) {
                "Session geometry does not match the selected video quality"
            }
            val connection = DirectControlConnection.connect(endpoint.host, endpoint.controlPort)
            controlConnection = connection
            emit("control_connection_started", mapOf("host" to endpoint.host, "port" to endpoint.controlPort))
            connection.send(
                DirectStreamContract.hello(
                    sessionId = endpoint.sessionId,
                    generation = endpoint.generation,
                    profileId = streamConfiguration.profile.id,
                    codedWidth = sessionTransform.codedWidth,
                    codedHeight = sessionTransform.codedHeight,
                    rotationDegrees = sessionTransform.rotationDegrees,
                    fps = streamConfiguration.profile.fps,
                    bitrateBps = streamConfiguration.bitrateBps,
                ),
            )
            val accepted = connection.receive()
                ?: error("Receiver closed the control connection before accepting the stream")
            check(accepted.requireProtocolVersion() == DirectStreamContract.PROTOCOL_VERSION) {
                "Receiver returned an unsupported direct protocol version"
            }
            check(accepted.stringField("type") == DirectStreamContract.MESSAGE_ACCEPTED) {
                accepted.stringFieldOrNull("error") ?: "Receiver rejected stream"
            }
            check(accepted.stringField("sessionId") == endpoint.sessionId) { "Receiver returned a different session" }
            check(accepted.longField("generation") == endpoint.generation) { "Receiver returned a different generation" }
            val mediaPort = accepted.intField("mediaPort")
            require(mediaPort in DirectStreamContract.MINIMUM_PORT..DirectStreamContract.MAXIMUM_PORT) {
                "Receiver returned an invalid media port"
            }
            val socket = withContext(Dispatchers.IO) {
                DatagramSocket().apply {
                    sendBufferSize = UDP_SEND_BUFFER_BYTES
                    connect(InetSocketAddress(endpoint.host, mediaPort))
                }
            }
            udpSocket = socket
            streamEndpoint = endpoint.copy(mediaPort = mediaPort)
            codec?.start()
            camera.start(
                encoderSurface ?: error("Encoder surface is unavailable"),
                targetFps = streamConfiguration.profile.fps,
            )
            running = true
            startSenderLoop(socket)
            startControlReader(connection)
            emit("stream_started", mapOf("mediaPort" to mediaPort, "generation" to endpoint.generation))
        }.recoverCatching { cause ->
            stopLocked(sendStop = false)
            releaseCodec()
            configuration = null
            prepared = false
            throw cause
        }
    }

    override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = lifecycleMutex.withLock {
        runCatching {
            check(bitrateBps in DirectStreamContract.MINIMUM_BITRATE_BPS..DirectStreamContract.MAXIMUM_BITRATE_BPS) {
                "Bitrate is outside the direct stream contract"
            }
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps) })
                ?: error("The encoder is not prepared")
            emit("encoder_bitrate_changed", mapOf("bitrateBps" to bitrateBps))
        }
    }

    override suspend fun stop(): Result<Unit> = lifecycleMutex.withLock {
        runCatching { stopLocked(sendStop = true) }
    }

    override suspend fun release() = lifecycleMutex.withLock {
        stopLocked(sendStop = false)
        camera.stop()
        releaseCodec()
        configuration = null
        prepared = false
        emit("stream_resources_released")
    }

    override suspend fun setPreviewSurface(surface: CameraPreviewSurface?) = lifecycleMutex.withLock {
        previewSurface = surface
        camera.setPreviewSurface(surface)
    }

    override suspend fun prepareCamera() = lifecycleMutex.withLock {
        camera.prepare()
    }

    override suspend fun setZoomRatio(zoomRatio: Float) = lifecycleMutex.withLock {
        camera.setZoomRatio(zoomRatio)
    }

    override suspend fun resetZoom() = lifecycleMutex.withLock {
        camera.resetZoom()
    }

    override suspend fun setStabilizationEnabled(enabled: Boolean) = lifecycleMutex.withLock {
        camera.setStabilizationEnabled(enabled)
    }

    override suspend fun selectPhysicalLens(lens: PhysicalLensOption) = lifecycleMutex.withLock {
        camera.selectPhysicalLens(lens)
    }

    private fun resetTelemetry() {
        encodedQueueDrops.set(EMPTY_LONG_VALUE)
        maximumEncodedQueueOccupancy.set(EMPTY_BYTE_COUNT)
        encodedAccessUnits.set(EMPTY_LONG_VALUE)
        encodedBytes.set(EMPTY_LONG_VALUE)
        encodedKeyFrames.set(EMPTY_LONG_VALUE)
        rtpPacketsSent.set(EMPTY_LONG_VALUE)
        rtpBytesSent.set(EMPTY_LONG_VALUE)
        udpSendErrors.set(EMPTY_LONG_VALUE)
        maximumUdpSendDurationNs.set(EMPTY_LONG_VALUE)
        lastSenderSummaryNs.set(EMPTY_LONG_VALUE)
    }

    private fun prepareCodec(configuration: StreamConfiguration) {
        val thread = HandlerThread(CODEC_THREAD_NAME)
        thread.start()
        codecThread = thread
        codecHandler = Handler(thread.looper)
        val encoder = MediaCodec.createEncoderByType(H264_MIME_TYPE)
        codec = encoder
        val format = MediaFormat.createVideoFormat(
            H264_MIME_TYPE,
            configuration.profile.width,
            configuration.profile.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, configuration.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, configuration.profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, configuration.keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, LOWEST_PRIORITY)
            setInteger(MediaFormat.KEY_OPERATING_RATE, configuration.profile.fps)
            setInteger(KEY_MAX_B_FRAMES, NO_B_FRAMES)
        }
        encoder.setCallback(codecCallback, codecHandler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = encoder.createInputSurface()
        emit(
            "encoder_configuration_requested",
            mapOf(
                "mime" to H264_MIME_TYPE,
                "width" to configuration.profile.width,
                "height" to configuration.profile.height,
                "fps" to configuration.profile.fps,
                "bitrateBps" to configuration.bitrateBps,
                "bitrateMode" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                "priority" to LOWEST_PRIORITY,
                "operatingRate" to configuration.profile.fps,
                "iFrameIntervalSeconds" to configuration.keyframeIntervalSeconds,
                "bFrames" to NO_B_FRAMES,
            ),
        )
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > EMPTY_BYTE_COUNT) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != EMPTY_BYTE_COUNT) {
                        codecConfigAnnexB = bytes.toAnnexB()
                        emit("encoder_codec_config", mapOf("bytes" to codecConfigAnnexB.size))
                    } else {
                        val normalized = bytes.toAnnexB()
                        val accessUnit = if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != EMPTY_BYTE_COUNT &&
                            codecConfigAnnexB.isNotEmpty()
                        ) {
                            codecConfigAnnexB + normalized
                        } else {
                            normalized
                        }
                        encodedAccessUnits.incrementAndGet()
                        encodedBytes.addAndGet(accessUnit.size.toLong())
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != EMPTY_BYTE_COUNT) {
                            encodedKeyFrames.incrementAndGet()
                        }
                        enqueueAccessUnit(
                            EncodedAccessUnit(
                                data = accessUnit,
                                presentationTimeUs = info.presentationTimeUs,
                                isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != EMPTY_BYTE_COUNT,
                            ),
                        )
                    }
                }
            }.onFailure { error -> emit("encoder_output_failed", mapOf("reason" to error.message)) }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            codecConfigAnnexB = format.extractCodecConfig()
            emit(
                "encoder_output_format",
                mapOf("format" to format.toString(), "codecConfigBytes" to codecConfigAnnexB.size),
            )
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            emit("encoder_error", mapOf("reason" to e.diagnosticInfo, "recoverable" to e.isRecoverable))
            eventFlow.tryEmit(StreamEngineEvent.ConnectionFailed(e.diagnosticInfo))
        }
    }

    private fun enqueueAccessUnit(accessUnit: EncodedAccessUnit) {
        if (encodedQueue.offer(accessUnit)) {
            observeQueueOccupancy()
            return
        }
        encodedQueue.clear()
        encodedQueue.offer(accessUnit)
        encodedQueueDrops.incrementAndGet()
        observeQueueOccupancy()
        emit("encoded_queue_drop", mapOf("maximum" to DirectStreamContract.MAXIMUM_ENCODED_QUEUE))
    }

    private fun observeQueueOccupancy() {
        maximumEncodedQueueOccupancy.updateAndGet { maximum ->
            maxOf(maximum, encodedQueue.size)
        }
    }

    private fun startSenderLoop(socket: DatagramSocket) {
        nextSequence.set((System.nanoTime() and SEQUENCE_MASK.toLong()).toInt())
        ssrc = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt()
        senderJob = workerScope.launch {
            val packetizer = RtpPacketizer { packet ->
                val startedAt = System.nanoTime()
                runCatching {
                    socket.send(DatagramPacket(packet, packet.size))
                    rtpPacketsSent.incrementAndGet()
                    rtpBytesSent.addAndGet(packet.size.toLong())
                    true
                }.getOrElse {
                    udpSendErrors.incrementAndGet()
                    false
                }.also {
                    val duration = System.nanoTime() - startedAt
                    maximumUdpSendDurationNs.updateAndGet { maximum -> maxOf(maximum, duration) }
                }
            }
            while (isActive) {
                val accessUnit = encodedQueue.poll(ENCODED_QUEUE_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    ?: continue
                val next = packetizer.sendAccessUnit(
                    annexB = accessUnit.data,
                    timestampUs = accessUnit.presentationTimeUs,
                    initialSequence = nextSequence.get(),
                    ssrc = ssrc,
                ).getOrThrow()
                nextSequence.set(next)
                maybeEmitSenderSummary()
                emit(
                    "rtp_access_unit_sent",
                    mapOf(
                        "bytes" to accessUnit.data.size,
                        "keyFrame" to accessUnit.isKeyFrame,
                        "presentationTimeUs" to accessUnit.presentationTimeUs,
                    ),
                )
            }
        }
    }

    private fun maybeEmitSenderSummary() {
        val now = System.nanoTime()
        val previous = lastSenderSummaryNs.get()
        if (previous != EMPTY_LONG_VALUE && now - previous < TELEMETRY_INTERVAL_NS) return
        if (!lastSenderSummaryNs.compareAndSet(previous, now)) return
        emit(
            "rtp_sender_summary",
            mapOf(
                "encodedAccessUnits" to encodedAccessUnits.get(),
                "encodedBytes" to encodedBytes.get(),
                "encodedKeyFrames" to encodedKeyFrames.get(),
                "encodedQueueOccupancy" to encodedQueue.size,
                "encodedQueueMaximum" to maximumEncodedQueueOccupancy.get(),
                "encodedQueueBound" to DirectStreamContract.MAXIMUM_ENCODED_QUEUE,
                "encodedQueueDrops" to encodedQueueDrops.get(),
                "rtpPacketsSent" to rtpPacketsSent.get(),
                "rtpBytesSent" to rtpBytesSent.get(),
                "udpSendErrors" to udpSendErrors.get(),
                "maximumUdpSendDurationNs" to maximumUdpSendDurationNs.get(),
            ),
        )
    }

    private fun startControlReader(connection: DirectControlConnection) {
        controlReaderJob = workerScope.launch {
            try {
                while (true) {
                    val message = connection.receive() ?: break
                    if (message.requireProtocolVersion() != DirectStreamContract.PROTOCOL_VERSION) continue
                    when (message.stringFieldOrNull("type")) {
                        "error" -> emit("receiver_control_error", mapOf("reason" to message.stringFieldOrNull("error")))
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                emit("control_reader_failed", mapOf("reason" to error.message))
            }
            if (running) {
                eventFlow.tryEmit(StreamEngineEvent.Disconnected)
            }
        }
    }

    private suspend fun stopLocked(sendStop: Boolean) {
        val endpoint = streamEndpoint
        if (sendStop && endpoint != null) {
            runCatching {
                controlConnection?.send(DirectStreamContract.stop(endpoint.sessionId, endpoint.generation))
            }
        }
        running = false
        senderJob?.cancelAndJoin()
        senderJob = null
        controlReaderJob?.cancel()
        controlConnection?.close()
        controlReaderJob?.join()
        controlReaderJob = null
        encodedQueue.clear()
        udpSocket?.close()
        udpSocket = null
        controlConnection = null
        streamEndpoint = null
        runCatching { camera.stop() }
        runCatching { codec?.stop() }
        emit("stream_stopped")
    }

    private fun releaseCodec() {
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        runCatching { codec?.release() }
        codec = null
        codecHandler = null
        val thread = codecThread
        thread?.quitSafely()
        thread?.join(CODEC_THREAD_JOIN_TIMEOUT_MILLIS)
        if (thread?.isAlive == true) {
            emit("codec_thread_join_timeout")
        }
        codecThread = null
        codecConfigAnnexB = ByteArray(EMPTY_BYTE_COUNT)
        encodedQueue.clear()
    }

    private fun emit(name: String, fields: Map<String, Any?> = emptyMap()) {
        val endpoint = streamEndpoint
        logger.event(
            name,
            (fields + mapOf(
                "protocolVersion" to DirectStreamContract.PROTOCOL_VERSION,
                "sessionId" to endpoint?.sessionId,
                "generation" to endpoint?.generation,
            )).filterValues { it != null },
        )
    }

    private data class EncodedAccessUnit(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean,
    )

    private fun ByteArray.toAnnexB(): ByteArray {
        if (startsWithStartCode()) return this
        if (firstOrNull()?.toInt()?.and(BYTE_MASK) == AVC_CONFIGURATION_VERSION) return avcConfigurationToAnnexB()
        val output = ArrayList<Byte>(size + H264_START_CODE_BYTES)
        var offset = EMPTY_BYTE_COUNT
        while (offset + LENGTH_PREFIX_BYTES <= size) {
            val nalSize = ((this[offset].toInt() and BYTE_MASK) shl 24) or
                ((this[offset + ONE_BYTE_OFFSET].toInt() and BYTE_MASK) shl 16) or
                ((this[offset + TWO_BYTE_OFFSET].toInt() and BYTE_MASK) shl 8) or
                (this[offset + THREE_BYTE_OFFSET].toInt() and BYTE_MASK)
            offset += LENGTH_PREFIX_BYTES
            require(nalSize > EMPTY_BYTE_COUNT && offset + nalSize <= size) { "Malformed AVC length-prefixed output" }
            repeat(H264_START_CODE_BYTES - ONE_BYTE_OFFSET) { output.add(ZERO_BYTE) }
            output.add(ONE_BYTE)
            repeat(nalSize) { index -> output.add(this[offset + index]) }
            offset += nalSize
        }
        require(offset == size) { "Malformed AVC output buffer" }
        return output.toByteArray()
    }

    private fun ByteArray.avcConfigurationToAnnexB(): ByteArray {
        require(size >= AVC_CONFIGURATION_HEADER_BYTES) { "AVC configuration is truncated" }
        val lengthBytes = (this[AVC_LENGTH_SIZE_OFFSET].toInt() and AVC_LENGTH_SIZE_MASK) + ONE_BYTE_OFFSET
        require(lengthBytes == LENGTH_PREFIX_BYTES) { "Unsupported AVC NAL length size" }
        var offset = AVC_CONFIGURATION_HEADER_BYTES
        val spsCount = this[offset].toInt() and AVC_SPS_COUNT_MASK
        offset += ONE_BYTE_OFFSET
        val output = ArrayList<Byte>()
        repeat(spsCount) {
            val nal = readConfigurationNal(offset).also { offset = it.second }.first.toAnnexBNal()
            output.addAll(nal.toList())
        }
        require(offset < size) { "AVC configuration has no PPS count" }
        val ppsCount = this[offset].toInt() and AVC_PPS_COUNT_MASK
        offset += ONE_BYTE_OFFSET
        repeat(ppsCount) {
            val nal = readConfigurationNal(offset).also { offset = it.second }.first.toAnnexBNal()
            output.addAll(nal.toList())
        }
        return output.toByteArray()
    }

    private fun ByteArray.readConfigurationNal(offset: Int): Pair<ByteArray, Int> {
        require(offset + AVC_CONFIGURATION_NAL_LENGTH_BYTES <= size) { "AVC configuration NAL length is truncated" }
        val nalSize = ((this[offset].toInt() and BYTE_MASK) shl 8) or (this[offset + ONE_BYTE_OFFSET].toInt() and BYTE_MASK)
        val start = offset + AVC_CONFIGURATION_NAL_LENGTH_BYTES
        require(nalSize > EMPTY_BYTE_COUNT && start + nalSize <= size) { "AVC configuration NAL is truncated" }
        return copyOfRange(start, start + nalSize) to (start + nalSize)
    }

    private fun ByteArray.toAnnexBNal(): ByteArray = H264_START_CODE + this

    private fun MediaFormat.extractCodecConfig(): ByteArray {
        val sps = getByteBuffer("csd-0")?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
            ?: return ByteArray(EMPTY_BYTE_COUNT)
        val pps = getByteBuffer("csd-1")?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
            ?: return sps.toAnnexB()
        return sps.toAnnexB() + pps.toAnnexB()
    }

    private fun ByteArray.startsWithStartCode(): Boolean =
        size >= THREE_BYTE_START_CODE_BYTES && this[EMPTY_BYTE_COUNT] == ZERO_BYTE && this[ONE_BYTE_OFFSET] == ZERO_BYTE &&
            (this[TWO_BYTE_OFFSET] == ONE_BYTE ||
                (size >= FOUR_BYTE_START_CODE_BYTES && this[TWO_BYTE_OFFSET] == ZERO_BYTE && this[THREE_BYTE_OFFSET] == ONE_BYTE))

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 32
        const val H264_MIME_TYPE = "video/avc"
        const val H264_CODEC = "h264"
        const val CODEC_THREAD_NAME = "direct-webcam-codec"
        const val CODEC_THREAD_JOIN_TIMEOUT_MILLIS = 2_000L
        const val ENCODED_QUEUE_POLL_TIMEOUT_MILLIS = 100L
        const val TELEMETRY_INTERVAL_MILLIS = 1_000L
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val TELEMETRY_INTERVAL_NS = TELEMETRY_INTERVAL_MILLIS * NANOSECONDS_PER_MILLISECOND
        const val UDP_SEND_BUFFER_BYTES = 4 * 1024 * 1024
        const val LOWEST_PRIORITY = 0
        const val NO_B_FRAMES = 0
        const val EMPTY_BYTE_COUNT = 0
        const val EMPTY_LONG_VALUE = 0L
        const val ONE_BYTE_OFFSET = 1
        const val TWO_BYTE_OFFSET = 2
        const val THREE_BYTE_OFFSET = 3
        const val BYTE_MASK = 0xff
        val ZERO_BYTE = 0.toByte()
        val ONE_BYTE = 1.toByte()
        val H264_START_CODE = byteArrayOf(ZERO_BYTE, ZERO_BYTE, ZERO_BYTE, ONE_BYTE)
        const val KEY_MAX_B_FRAMES = "max-bframes"
        const val SEQUENCE_MASK = 0xffff
        const val THREE_BYTE_START_CODE_BYTES = 3
        const val FOUR_BYTE_START_CODE_BYTES = 4
        const val H264_START_CODE_BYTES = 4
        const val LENGTH_PREFIX_BYTES = 4
        const val AVC_CONFIGURATION_VERSION = 1
        const val AVC_CONFIGURATION_HEADER_BYTES = 6
        const val AVC_CONFIGURATION_NAL_LENGTH_BYTES = 2
        const val AVC_LENGTH_SIZE_OFFSET = 4
        const val AVC_LENGTH_SIZE_MASK = 3
        const val AVC_SPS_COUNT_MASK = 0x1f
        const val AVC_PPS_COUNT_MASK = 0xff
    }
}
