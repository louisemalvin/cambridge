package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.rootencoder.RootEncoderStreamEngine
import dev.mobilewebcam.sender.media.streaming.session.CodecNegotiator
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import dev.mobilewebcam.sender.platform.service.AndroidForegroundStreamingController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideRootEncoderStreamEngine(
        @ApplicationContext context: Context,
    ): RootEncoderStreamEngine = RootEncoderStreamEngine(context)

    @Provides
    fun provideStreamEngine(
        engine: RootEncoderStreamEngine,
    ): StreamEngine = engine

    @Provides
    fun provideCameraController(
        engine: RootEncoderStreamEngine,
    ): CameraController = engine

    @Provides
    @Singleton
    fun provideStreamSessionController(
        @ApplicationContext context: Context,
        engine: RootEncoderStreamEngine,
        powerManager: StreamingPowerManager,
    ): StreamSessionController = StreamSessionControllerImpl(
        receiver = HttpReceiverControlClient(),
        capabilityProbe = MediaCodecCapabilityProbe(),
        negotiator = CodecNegotiator(),
        streamEngine = engine,
        foreground = AndroidForegroundStreamingController(context, powerManager),
    )
}
