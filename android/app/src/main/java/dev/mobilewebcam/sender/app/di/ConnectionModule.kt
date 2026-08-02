package dev.mobilewebcam.sender.app.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
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
    ): SenderControlServer {
        val server = SenderControlServer(
            coordinator = coordinator,
            senderId = pairings.senderId,
            displayName = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android phone",
        )
        server.start()
        return server
    }
}
