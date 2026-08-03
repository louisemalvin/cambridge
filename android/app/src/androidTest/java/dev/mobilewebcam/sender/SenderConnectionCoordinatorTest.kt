package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.media.streaming.session.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderConnectionCoordinatorTest {
    @Test
    fun forgettingPairingStopsSessionAndClearsApproval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairings = PairingStore(context)
        val receiverId = "coordinator-test-receiver"
        val controller = FakeSessionController()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        pairings.forgetAll()
        pairings.approve(receiverId, "Test desktop", "127.0.0.1")

        val coordinator = SenderConnectionCoordinator(
            context = context,
            controller = controller,
            pairings = pairings,
            settings = FakeSettingsRepository,
            scope = scope,
        )

        try {
            assertTrue(coordinator.hasApprovedReceiver.value)
            assertTrue(coordinator.forgetPairing().isSuccess)
            assertFalse(pairings.hasApprovedReceivers())
            assertFalse(coordinator.hasApprovedReceiver.value)
            assertTrue(controller.stopCalled)
        } finally {
            pairings.forgetAll()
            scope.cancel()
        }
    }

    private class FakeSessionController : StreamSessionController {
        private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
        var stopCalled = false

        override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

        override suspend fun start(
            endpoint: ReceiverEndpoint,
            preference: CodecPreference,
            profile: dev.mobilewebcam.sender.model.VideoProfile,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> {
            stopCalled = true
            stateFlow.value = StreamState.Idle
            return Result.success(Unit)
        }

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = Result.success(Unit)
    }

    private object FakeSettingsRepository : SenderSettingsRepository {
        override val state: StateFlow<SenderSettings> = MutableStateFlow(
            SenderSettings(
                codecPreference = CodecPreference.AUTO_PREFER_H265,
                profile = VideoProfiles.default,
            ),
        )

        override fun updateCodecPreference(preference: CodecPreference) = Unit

        override fun updateProfile(profile: dev.mobilewebcam.sender.model.VideoProfile) = Unit
    }
}
