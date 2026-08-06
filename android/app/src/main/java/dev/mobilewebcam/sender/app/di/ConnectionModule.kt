package dev.mobilewebcam.sender.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mobilewebcam.sender.connection.NetworkChangeMonitor
import dev.mobilewebcam.sender.connection.ReconnectPolicy
import dev.mobilewebcam.sender.deployment.DirectDeployment
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides
    @Singleton
    fun provideNetworkChangeMonitor(
        @ApplicationContext context: Context,
    ): NetworkChangeMonitor = dev.mobilewebcam.sender.platform.network.AndroidNetworkChangeMonitor(context)

    @Provides
    @Singleton
    fun provideReconnectPolicy(): ReconnectPolicy = ReconnectPolicy()

    @Provides
    @Singleton
    fun provideSenderConnectionCoordinator(
        sessionController: StreamSessionController,
        settings: SenderSettingsRepository,
        networkChangeMonitor: NetworkChangeMonitor,
        reconnectPolicy: ReconnectPolicy,
    ): SenderConnectionCoordinator = SenderConnectionCoordinator(
        controller = sessionController,
        settings = settings,
        defaultEndpoint = DirectDeployment.endpoint,
        networkChangeMonitor = networkChangeMonitor,
        reconnectPolicy = reconnectPolicy,
    )
}
