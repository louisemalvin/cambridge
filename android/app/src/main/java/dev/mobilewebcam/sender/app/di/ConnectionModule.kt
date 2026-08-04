package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.connection.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.connection.control.http.ProtocolJson
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.AndroidReceiverDiscovery
import dev.mobilewebcam.sender.connection.discovery.ReceiverDiscovery
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.model.SenderSettingsRepository
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
        settings: SenderSettingsRepository,
    ): SenderConnectionCoordinator = SenderConnectionCoordinator(
        context = context,
        controller = sessionController,
        settings = settings,
    )

    @Provides
    @Singleton
    fun provideReceiverDiscovery(
        @ApplicationContext context: Context,
    ): ReceiverDiscovery = AndroidReceiverDiscovery(context)
}
