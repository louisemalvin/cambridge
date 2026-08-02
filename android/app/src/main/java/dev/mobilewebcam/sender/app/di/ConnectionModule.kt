package dev.mobilewebcam.sender.app.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.connection.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.connection.control.http.ProtocolJson
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(ProtocolJson.instance)
        }
    }

    @Provides
    @Singleton
    fun provideReceiverControlClient(
        client: HttpClient,
    ): ReceiverControlClient = HttpReceiverControlClient(client)

    @Provides
    @Singleton
    fun provideSenderConnectionCoordinator(
        @ApplicationContext context: Context,
        sessionController: StreamSessionController,
        pairings: PairingStore,
    ): SenderConnectionCoordinator = SenderConnectionCoordinator(
        context = context,
        controller = sessionController,
        pairings = pairings,
    )

    @Provides
    @Singleton
    fun provideSenderControlServer(
        coordinator: SenderConnectionCoordinator,
        pairings: PairingStore,
        logger: AppLogger,
    ): SenderControlServer = SenderControlServer(
        coordinator = coordinator,
        senderId = pairings.senderId,
        displayName = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android phone",
        logger = logger,
    )
}
