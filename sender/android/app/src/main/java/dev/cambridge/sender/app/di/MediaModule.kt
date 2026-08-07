package dev.cambridge.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.media.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.cambridge.sender.media.streaming.StreamEngine
import dev.cambridge.sender.media.streaming.cambridge.CamBridgeRtpStreamEngine
import dev.cambridge.sender.session.StreamSessionController
import dev.cambridge.sender.session.StreamSessionControllerImpl
import dev.cambridge.sender.platform.power.StreamingPowerManager
import dev.cambridge.sender.platform.service.AndroidForegroundStreamingController
import dev.cambridge.sender.platform.service.ForegroundStreamingController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideCamBridgeRtpStreamEngine(
        @ApplicationContext context: Context,
        logger: AppLogger,
    ): CamBridgeRtpStreamEngine = CamBridgeRtpStreamEngine(context, logger)

    @Provides
    @Singleton
    fun provideStreamEngine(
        engine: CamBridgeRtpStreamEngine,
    ): StreamEngine = engine

    @Provides
    @Singleton
    fun provideCameraController(
        engine: CamBridgeRtpStreamEngine,
    ): CameraController = engine

    @Provides
    @Singleton
    fun provideEncoderCapabilityProbe(): EncoderCapabilityProbe = MediaCodecCapabilityProbe()

    @Provides
    @Singleton
    fun provideForegroundStreamingController(
        @ApplicationContext context: Context,
        powerManager: StreamingPowerManager,
    ): ForegroundStreamingController = AndroidForegroundStreamingController(context, powerManager)

    @Provides
    @Singleton
    fun provideStreamSessionController(
        capabilityProbe: EncoderCapabilityProbe,
        streamEngine: StreamEngine,
        cameraController: CameraController,
        foreground: ForegroundStreamingController,
        logger: AppLogger,
    ): StreamSessionController = StreamSessionControllerImpl(
        capabilityProbe = capabilityProbe,
        streamEngine = streamEngine,
        cameraController = cameraController,
        foreground = foreground,
        logger = logger,
    )
}
