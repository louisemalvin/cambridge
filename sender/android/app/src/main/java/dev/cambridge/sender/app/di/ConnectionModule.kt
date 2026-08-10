package dev.cambridge.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cambridge.discovery.AndroidReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscoveryConfig
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.connection.control.ReceiverProbe
import dev.cambridge.sender.connection.control.cambridge.CamBridgeReceiverProbe
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.deployment.CamBridgeDeployment
import dev.cambridge.sender.session.StreamSessionController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {
    @Provides
    @Singleton
    fun provideReceiverProbe(): ReceiverProbe = CamBridgeReceiverProbe()

    @Provides
    @Singleton
    fun provideReceiverDiscovery(
        @ApplicationContext context: Context,
    ): ReceiverDiscovery = AndroidReceiverDiscovery(
        context = context,
        config = ReceiverDiscoveryConfig(
            serviceType = CamBridgeStreamContract.DISCOVERY_SERVICE_TYPE,
            addressAttributePrefix = CamBridgeStreamContract.DISCOVERY_ADDRESS_KEY_PREFIX,
            maximumAddressAttributeCount = CamBridgeStreamContract.MAXIMUM_DISCOVERY_ADDRESS_COUNT,
            addressFamily = CamBridgeStreamContract.DISCOVERY_ADDRESS_FAMILY,
        ),
    )

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
        defaultEndpoint = CamBridgeDeployment.endpoint,
        receiverProbe = receiverProbe,
        receiverDiscovery = receiverDiscovery,
    )
}
