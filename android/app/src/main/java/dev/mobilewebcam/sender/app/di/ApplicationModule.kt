package dev.mobilewebcam.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.platform.notification.NotificationFactory
import dev.mobilewebcam.sender.platform.preferences.SenderSettingsStore
import dev.mobilewebcam.sender.platform.power.AndroidStreamingPowerManager
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {
    @Provides
    @Singleton
    fun provideSenderSettingsRepository(
        @ApplicationContext context: Context,
    ): SenderSettingsRepository = SenderSettingsStore(context)

    @Provides
    @Singleton
    fun provideStreamingPowerManager(
        @ApplicationContext context: Context,
    ): StreamingPowerManager = AndroidStreamingPowerManager(context)

    @Provides
    @Singleton
    fun provideNotificationFactory(
        @ApplicationContext context: Context,
    ): NotificationFactory = NotificationFactory(context)

    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger = AndroidAppLogger
}
