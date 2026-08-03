package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.capabilities.EncoderCapabilityProbe
import dev.mobilewebcam.sender.media.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.rootencoder.RootEncoderStreamEngine
import dev.mobilewebcam.sender.session.CodecNegotiator
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import dev.mobilewebcam.sender.platform.service.AndroidForegroundStreamingController
import dev.mobilewebcam.sender.platform.service.ForegroundStreamingController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideRootEncoderStreamEngine(
        @ApplicationContext context: Context,
        logger: AppLogger,
    ): RootEncoderStreamEngine = RootEncoderStreamEngine(context, logger)

    @Provides
    @Singleton
    fun provideStreamEngine(
        engine: RootEncoderStreamEngine,
    ): StreamEngine = engine

    @Provides
    @Singleton
    fun provideCameraController(
        engine: RootEncoderStreamEngine,
    ): CameraController = engine

    @Provides
    @Singleton
    fun provideEncoderCapabilityProbe(): EncoderCapabilityProbe = MediaCodecCapabilityProbe()

    @Provides
    @Singleton
    fun provideCodecNegotiator(): CodecNegotiator = CodecNegotiator()

    @Provides
    @Singleton
    fun provideForegroundStreamingController(
        @ApplicationContext context: Context,
        powerManager: StreamingPowerManager,
    ): ForegroundStreamingController = AndroidForegroundStreamingController(context, powerManager)

    @Provides
    @Singleton
    fun provideStreamSessionController(
        receiver: ReceiverControlClient,
        capabilityProbe: EncoderCapabilityProbe,
        negotiator: CodecNegotiator,
        streamEngine: StreamEngine,
        foreground: ForegroundStreamingController,
        logger: AppLogger,
    ): StreamSessionController = StreamSessionControllerImpl(
        receiver = receiver,
        capabilityProbe = capabilityProbe,
        negotiator = negotiator,
        streamEngine = streamEngine,
        foreground = foreground,
        logger = logger,
    )
}
