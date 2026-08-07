package dev.cambridge.sender.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.platform.notification.NotificationFactory
import dev.cambridge.sender.platform.preferences.SenderSettingsStore
import dev.cambridge.sender.platform.power.AndroidStreamingPowerManager
import dev.cambridge.sender.platform.power.StreamingPowerManager
import dev.cambridge.sender.model.SenderSettingsRepository
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
