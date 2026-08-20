package dev.cambridge.sender.media.streaming.cambridge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.cambridge.sender.connection.control.cambridge.CamBridgeControlConnection
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.intField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.longField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.requireProtocolVersion
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.stringField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.stringFieldOrNull
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraPermissionRequiredException
import dev.cambridge.sender.media.camera.CameraPreviewSurface
import dev.cambridge.sender.media.camera.PhysicalLensOption
import dev.cambridge.sender.media.camera.SessionTransform
import dev.cambridge.sender.media.streaming.StreamEngine
import dev.cambridge.sender.media.streaming.StreamEngineEvent
import dev.cambridge.sender.model.CamBridgeStreamEndpoint
import dev.cambridge.sender.model.StreamConfiguration
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamFailureException
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
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

class CamBridgeRtpStreamEngine(
    context: Context,
    private val logger: AppLogger = AndroidAppLogger,
) : StreamEngine, CameraController {
    private val applicationContext = context.applicationContext
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val camera = Camera2Capture(applicationContext, logger)
    private val lifecycleMutex = Mutex()
    private val stateFlow = MutableStateFlow<CameraInteractionState>(CameraInteractionState.inactive())
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encodedQueue = ArrayBlockingQueue<EncodedAccessUnit>(CamBridgeStreamContract.MAXIMUM_ENCODED_QUEUE)
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var encoderSurface: Surface? = null
    @Volatile
    private var configuration: StreamConfiguration? = null
    private var controlConnection: CamBridgeControlConnection? = null
    private var udpSocket: DatagramSocket? = null
    private var senderJob: Job? = null
    private var controlReaderJob: Job? = null
    private var previewSurface: CameraPreviewSurface? = null
    @Volatile
    private var codecConfigAnnexB = ByteArray(EMPTY_BYTE_COUNT)
    @Volatile
    private var streamEndpoint: CamBridgeStreamEndpoint? = null
    private var nextSequence = AtomicInteger(EMPTY_BYTE_COUNT)
    private var ssrc = EMPTY_BYTE_COUNT
    private var prepared = false
    @Volatile
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

    override suspend fun supportedVideoModes(modes: List<VideoProfile>): Set<String> = lifecycleMutex.withLock {
        camera.supportedVideoModes(modes)
    }

    override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> = lifecycleMutex.withLock {
        try {
            check(!prepared) { "The CamBridge sender is already prepared" }
            require(configuration.codec.protocolId == H264_CODEC) { "The CamBridge sender supports H.264 only" }
            resetTelemetry()
            this.configuration = configuration
            camera.setDiagnosticsContext(configuration.runId, configuration.sessionId)
            try {
                camera.prepare()
            } catch (cause: Throwable) {
                if (cause is CameraPermissionRequiredException) throw cause
                throw StreamFailureException(StreamFailure.CameraUnavailable, cause)
            }
            prepareCodec(configuration)
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
            this@CamBridgeRtpStreamEngine.configuration = null
            prepared = false
            throw cancelled
        } catch (cause: Throwable) {
            runCatching { camera.stop() }
            releaseCodec()
            this@CamBridgeRtpStreamEngine.configuration = null
            prepared = false
            Result.failure(cause)
        }
    }

    override suspend fun start(endpoint: CamBridgeStreamEndpoint): Result<Unit> = lifecycleMutex.withLock {
        runCatching {
            check(prepared) { "The CamBridge sender has not been prepared" }
            check(!running) { "The CamBridge sender is already running" }
            val streamConfiguration = configuration ?: error("Stream configuration is unavailable")
            val sessionTransform = streamConfiguration.sessionTransform
                ?: error("The session contract must be resolved before streaming")
            require(sessionTransform.codedWidth == streamConfiguration.profile.width &&
                sessionTransform.codedHeight == streamConfiguration.profile.height) {
                "Session geometry does not match the selected video quality"
            }
            val connection = try {
                CamBridgeControlConnection.connect(
                    host = endpoint.host,
                    port = endpoint.controlPort,
                    readTimeoutMillis = CamBridgeStreamContract.REQUEST_TIMEOUT_MILLIS,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                throw receiverUnavailable("The receiver did not respond", cause)
            }
            controlConnection = connection
            emit("control_connection_started", mapOf("host" to endpoint.host, "port" to endpoint.controlPort))
            val accepted = try {
                connection.send(
                    CamBridgeStreamContract.hello(
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
                val response = connection.receive()
                    ?: error("The receiver did not respond before the control response deadline")
                check(response.requireProtocolVersion() == CamBridgeStreamContract.PROTOCOL_VERSION) {
                    "Receiver returned an unsupported CamBridge protocol version"
                }
                check(response.stringField("type") == CamBridgeStreamContract.MESSAGE_ACCEPTED) {
                    response.stringFieldOrNull("error") ?: "Receiver rejected stream"
                }
                check(response.stringField("sessionId") == endpoint.sessionId) {
                    "Receiver returned a different session"
                }
                check(response.longField("generation") == endpoint.generation) {
                    "Receiver returned a different generation"
                }
                response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: StreamFailureException) {
                throw failure
            } catch (cause: Throwable) {
                throw receiverUnavailable(cause.message ?: "The receiver rejected the stream", cause)
            }
            connection.clearReadTimeout()
            val mediaPort = accepted.intField("mediaPort")
            require(mediaPort in CamBridgeStreamContract.MINIMUM_PORT..CamBridgeStreamContract.MAXIMUM_PORT) {
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
            try {
                camera.start(
                    encoderSurface ?: error("Encoder surface is unavailable"),
                    targetFps = streamConfiguration.profile.fps,
                    codedWidth = streamConfiguration.profile.width,
                    codedHeight = streamConfiguration.profile.height,
                )
            } catch (cause: Throwable) {
                if (cause is CameraPermissionRequiredException) throw cause
                throw StreamFailureException(StreamFailure.CameraUnavailable, cause)
            }
            running = true
            startSenderLoop(socket, endpoint.generation)
            startControlReader(connection, endpoint.generation)
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
            check(bitrateBps in CamBridgeStreamContract.MINIMUM_BITRATE_BPS..CamBridgeStreamContract.MAXIMUM_BITRATE_BPS) {
                "Bitrate is outside the CamBridge stream contract"
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

    override suspend fun setStabilizationMode(mode: dev.cambridge.sender.media.camera.CameraStabilizationMode) =
        lifecycleMutex.withLock {
            camera.setStabilizationMode(mode)
        }

    override suspend fun setAntiFlickerMode(mode: AntiFlickerMode) = lifecycleMutex.withLock {
        camera.setAntiFlickerMode(mode)
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

    private fun receiverUnavailable(reason: String, cause: Throwable): StreamFailureException =
        StreamFailureException(StreamFailure.ReceiverUnavailable(reason), cause)

    private fun prepareCodec(configuration: StreamConfiguration) {
        val thread = HandlerThread(CODEC_THREAD_NAME)
        thread.start()
        codecThread = thread
        codecHandler = Handler(thread.looper)
        val encoder = MediaCodec.createByCodecName(configuration.encoderName)
        codec = encoder
        val format = MediaFormat.createVideoFormat(
            H264_MIME_TYPE,
            configuration.profile.width,
            configuration.profile.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_BIT_RATE, configuration.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, configuration.profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, configuration.keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_PRIORITY, LOWEST_PRIORITY)
            setInteger(MediaFormat.KEY_OPERATING_RATE, configuration.profile.fps)
            setInteger(KEY_MAX_B_FRAMES, NO_B_FRAMES)
        }
        encoder.setCallback(codecCallback(configuration.streamGeneration), codecHandler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = encoder.createInputSurface()
        emit(
            "encoder_configuration_requested",
            mapOf(
                "mime" to H264_MIME_TYPE,
                "encoder" to configuration.encoderName,
                "colorStandard" to MediaFormat.COLOR_STANDARD_BT709,
                "colorRange" to MediaFormat.COLOR_RANGE_LIMITED,
                "colorTransfer" to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                "width" to configuration.profile.width,
                "height" to configuration.profile.height,
                "fps" to configuration.profile.fps,
                "bitrateBps" to configuration.bitrateBps,
                "priority" to LOWEST_PRIORITY,
                "operatingRate" to configuration.profile.fps,
                "iFrameIntervalSeconds" to configuration.keyframeIntervalSeconds,
                "bFrames" to NO_B_FRAMES,
            ),
        )
    }

    private fun codecCallback(generation: Long) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (configuration?.streamGeneration == generation) {
                runCatching {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > EMPTY_BYTE_COUNT) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != EMPTY_BYTE_COUNT) {
                            codecConfigAnnexB = H264AccessUnitNormalizer.normalizeCodecConfiguration(bytes)
                            emit("encoder_codec_config", mapOf("bytes" to codecConfigAnnexB.size))
                        } else {
                            val normalized = H264AccessUnitNormalizer.normalize(bytes)
                            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != EMPTY_BYTE_COUNT
                            if (codecConfigAnnexB.isEmpty() &&
                                H264AccessUnitNormalizer.containsRequiredParameterSets(normalized)
                            ) {
                                codecConfigAnnexB = H264AccessUnitNormalizer.parameterSetsFromAnnexB(normalized)
                            }
                            require(
                                codecConfigAnnexB.isNotEmpty() ||
                                    H264AccessUnitNormalizer.containsRequiredParameterSets(normalized),
                            ) {
                                "H.264 media arrived before SPS and PPS"
                            }
                            val accessUnit = if (isKeyFrame &&
                                codecConfigAnnexB.isNotEmpty() &&
                                !H264AccessUnitNormalizer.containsRequiredParameterSets(normalized)
                            ) {
                                codecConfigAnnexB + normalized
                            } else {
                                normalized
                            }
                            require(accessUnit.size <= CamBridgeStreamContract.MAXIMUM_ACCESS_UNIT_BYTES) {
                                "H.264 access unit exceeds the configured maximum"
                            }
                            encodedAccessUnits.incrementAndGet()
                            encodedBytes.addAndGet(accessUnit.size.toLong())
                            if (isKeyFrame) {
                                encodedKeyFrames.incrementAndGet()
                            }
                            enqueueAccessUnit(
                                EncodedAccessUnit(
                                    data = accessUnit,
                                    presentationTimeUs = info.presentationTimeUs,
                                    isKeyFrame = isKeyFrame,
                                ),
                            )
                        }
                    }
                }.onFailure { error ->
                    emit("encoder_output_failed", mapOf("reason" to error.message))
                    eventFlow.tryEmit(
                        StreamEngineEvent.FatalFailure(
                            StreamFailure.EncoderPreparationFailed(
                                codec = VideoCodec.H264,
                                cause = error,
                            ),
                            generation = generation,
                        ),
                    )
                }
            }
            runCatching {
                codec.releaseOutputBuffer(index, false)
            }.onFailure { error ->
                if (configuration?.streamGeneration == generation) {
                    emit("encoder_output_release_failed", mapOf("reason" to error.message))
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (configuration?.streamGeneration == generation) {
                runCatching {
                    codecConfigAnnexB = format.extractCodecConfig()
                    emit(
                        "encoder_output_format",
                        mapOf("format" to format.toString(), "codecConfigBytes" to codecConfigAnnexB.size),
                    )
                }.onFailure { error ->
                    emit("encoder_output_format_failed", mapOf("reason" to error.message))
                    eventFlow.tryEmit(
                        StreamEngineEvent.FatalFailure(
                            StreamFailure.EncoderPreparationFailed(
                                codec = VideoCodec.H264,
                                cause = error,
                            ),
                            generation = generation,
                        ),
                    )
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (configuration?.streamGeneration == generation) {
                emit("encoder_error", mapOf("reason" to e.diagnosticInfo, "recoverable" to e.isRecoverable))
                eventFlow.tryEmit(
                    StreamEngineEvent.FatalFailure(
                        StreamFailure.EncoderPreparationFailed(
                            codec = VideoCodec.H264,
                            cause = e,
                        ),
                        generation = generation,
                    ),
                )
            }
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
        emit("encoded_queue_drop", mapOf("maximum" to CamBridgeStreamContract.MAXIMUM_ENCODED_QUEUE))
    }

    private fun observeQueueOccupancy() {
        maximumEncodedQueueOccupancy.updateAndGet { maximum ->
            maxOf(maximum, encodedQueue.size)
        }
    }

    private fun startSenderLoop(socket: DatagramSocket, generation: Long) {
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
            while (isActive && isCurrentRunningGeneration(generation)) {
                val accessUnit = encodedQueue.poll(ENCODED_QUEUE_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    ?: continue
                val next = packetizer.sendAccessUnit(
                    annexB = accessUnit.data,
                    timestampUs = accessUnit.presentationTimeUs,
                    initialSequence = nextSequence.get(),
                    ssrc = ssrc,
                ).getOrElse { error ->
                    eventFlow.tryEmit(
                        StreamEngineEvent.FatalFailure(
                            StreamFailure.RtpTransportFailed(error),
                            generation = generation,
                        ),
                    )
                    return@launch
                }
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
                "encodedQueueBound" to CamBridgeStreamContract.MAXIMUM_ENCODED_QUEUE,
                "encodedQueueDrops" to encodedQueueDrops.get(),
                "rtpPacketsSent" to rtpPacketsSent.get(),
                "rtpBytesSent" to rtpBytesSent.get(),
                "udpSendErrors" to udpSendErrors.get(),
                "maximumUdpSendDurationNs" to maximumUdpSendDurationNs.get(),
            ),
        )
    }

    private fun startControlReader(connection: CamBridgeControlConnection, generation: Long) {
        controlReaderJob = workerScope.launch {
            try {
                while (isCurrentRunningGeneration(generation)) {
                    val message = connection.receive() ?: break
                    if (message.requireProtocolVersion() != CamBridgeStreamContract.PROTOCOL_VERSION) continue
                    when (message.stringFieldOrNull("type")) {
                        "error" -> {
                            if (!isCurrentRunningGeneration(generation)) break
                            val reason = message.stringFieldOrNull("error")
                            emit("receiver_control_error", mapOf("reason" to reason))
                            eventFlow.tryEmit(
                                StreamEngineEvent.FatalFailure(
                                    StreamFailure.ReceiverUnavailable(
                                        reason ?: "The receiver rejected the stream",
                                    ),
                                    generation = generation,
                                ),
                            )
                            break
                        }
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentRunningGeneration(generation)) {
                    emit("control_reader_failed", mapOf("reason" to error.message))
                }
            }
            if (isCurrentRunningGeneration(generation)) {
                eventFlow.tryEmit(StreamEngineEvent.Disconnected(generation))
            }
        }
    }

    private fun isCurrentRunningGeneration(generation: Long): Boolean =
        running && streamEndpoint?.generation == generation

    private suspend fun stopLocked(sendStop: Boolean) {
        val endpoint = streamEndpoint
        if (sendStop && endpoint != null) {
            runCatching {
                controlConnection?.send(CamBridgeStreamContract.stop(endpoint.sessionId, endpoint.generation))
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
                "protocolVersion" to CamBridgeStreamContract.PROTOCOL_VERSION,
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

    private fun MediaFormat.extractCodecConfig(): ByteArray {
        val spsBuffer = getByteBuffer("csd-0")
        val ppsBuffer = getByteBuffer("csd-1")
        if (spsBuffer == null || ppsBuffer == null) {
            require(spsBuffer == null && ppsBuffer == null) {
                "H.264 output format contains only one parameter set"
            }
            return ByteArray(EMPTY_BYTE_COUNT)
        }
        val sps = ByteArray(spsBuffer.remaining()).also(spsBuffer::get)
        val pps = ByteArray(ppsBuffer.remaining()).also(ppsBuffer::get)
        return H264AccessUnitNormalizer.normalizeParameterSets(sps, pps)
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 32
        const val H264_MIME_TYPE = "video/avc"
        const val H264_CODEC = "h264"
        const val CODEC_THREAD_NAME = "cambridge-codec"
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
        const val KEY_MAX_B_FRAMES = "max-bframes"
        const val SEQUENCE_MASK = 0xffff
    }
}
