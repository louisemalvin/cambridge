package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.control.ReceiverDiscovery
import dev.mobilewebcam.sender.connection.control.ReceiverProbe
import dev.mobilewebcam.sender.connection.control.direct.DirectReceiverProbe
import dev.mobilewebcam.sender.connection.discovery.AndroidReceiverDiscovery
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.deployment.DirectDeployment
import dev.mobilewebcam.sender.session.StreamSessionController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides
    @Singleton
    fun provideReceiverProbe(): ReceiverProbe = DirectReceiverProbe()

    @Provides
    @Singleton
    fun provideReceiverDiscovery(
        @ApplicationContext context: Context,
    ): ReceiverDiscovery = AndroidReceiverDiscovery(context)

    @Provides
    @Singleton
    fun provideSenderConnectionCoordinator(
        sessionController: StreamSessionController,
        settings: SenderSettingsRepository,
        receiverProbe: ReceiverProbe,
        receiverDiscovery: ReceiverDiscovery,
    ): SenderConnectionCoordinator = SenderConnectionCoordinator(
        controller = sessionController,
        settings = settings,
        defaultEndpoint = DirectDeployment.endpoint,
        receiverProbe = receiverProbe,
        receiverDiscovery = receiverDiscovery,
    )
}
