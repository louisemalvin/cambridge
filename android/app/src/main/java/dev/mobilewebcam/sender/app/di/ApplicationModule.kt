package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.platform.power.AndroidStreamingPowerManager
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {
    @Provides
    @Singleton
    fun providePairingStore(
        @ApplicationContext context: Context,
    ): PairingStore = PairingStore(context)

    @Provides
    @Singleton
    fun provideStreamingPowerManager(
        @ApplicationContext context: Context,
    ): StreamingPowerManager = AndroidStreamingPowerManager(context)
}
