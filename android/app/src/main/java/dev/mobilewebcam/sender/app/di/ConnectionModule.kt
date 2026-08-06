package dev.mobilewebcam.sender.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.deployment.DirectDeployment
import dev.mobilewebcam.sender.connection.control.ReceiverProbe
import dev.mobilewebcam.sender.connection.control.direct.DirectReceiverProbe
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides
    @Singleton
    fun provideReceiverProbe(): ReceiverProbe = DirectReceiverProbe()

    @Provides
    @Singleton
    fun provideSenderConnectionCoordinator(
        sessionController: StreamSessionController,
        settings: SenderSettingsRepository,
        receiverProbe: ReceiverProbe,
    ): SenderConnectionCoordinator = SenderConnectionCoordinator(
        controller = sessionController,
        settings = settings,
        defaultEndpoint = DirectDeployment.endpoint,
        receiverProbe = receiverProbe,
    )
}
