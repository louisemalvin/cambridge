package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderControlActionDto
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.StartStreamRequestDto
import dev.mobilewebcam.sender.connection.discovery.StartStreamStatusDto
import dev.mobilewebcam.sender.connection.discovery.StopStreamRequestDto
import dev.mobilewebcam.sender.connection.discovery.StopStreamStatusDto
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.VideoProfiles
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
import org.junit.Assert.assertEquals
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

    @Test
    fun duplicateStartIsIdempotentAndStaleStopCannotStopTheActiveGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairings = PairingStore(context)
        val receiverId = "coordinator-stream-receiver"
        val controller = FakeSessionController()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        pairings.forgetAll()
        pairings.approve(receiverId, "Test desktop", PEER_ADDRESS)

        val coordinator = SenderConnectionCoordinator(
            context = context,
            controller = controller,
            pairings = pairings,
            settings = FakeSettingsRepository,
            scope = scope,
            cameraPermissionChecker = { true },
        )
        val streamId = "d3ebda88-5e25-4a47-99e4-44029adf49ef"
        val newerStreamId = "c2da1c77-4d14-3a36-88d3-33e18c3a48de"
        val first = coordinator.handleStartRequest(
            StartStreamRequestDto(
                protocolVersion = 2,
                action = SenderControlActionDto.START,
                streamId = streamId,
                receiverId = receiverId,
                receiverName = "Test desktop",
                receiverControlPort = 5001,
            ),
            PEER_ADDRESS,
        )
        val token = first.pairingToken ?: error("first accepted response must deliver a token")
        assertEquals(StartStreamStatusDto.ACCEPTED, first.status)

        val duplicate = coordinator.handleStartRequest(
            StartStreamRequestDto(
                protocolVersion = 2,
                action = SenderControlActionDto.START,
                streamId = streamId,
                receiverId = receiverId,
                receiverName = "Test desktop",
                receiverControlPort = 5001,
                pairingToken = token,
            ),
            PEER_ADDRESS,
        )
        assertEquals(StartStreamStatusDto.ACCEPTED, duplicate.status)
        assertEquals(1, controller.startCount)

        val rejectedStop = coordinator.handleStopRequest(
            StopStreamRequestDto(2, SenderControlActionDto.STOP, streamId, receiverId, "wrong-token"),
            PEER_ADDRESS,
        )
        assertEquals(StopStreamStatusDto.REJECTED, rejectedStop.status)
        assertEquals(0, controller.stopCount)

        val staleStop = coordinator.handleStopRequest(
            StopStreamRequestDto(2, SenderControlActionDto.STOP, newerStreamId, receiverId, token),
            PEER_ADDRESS,
        )
        assertEquals(StopStreamStatusDto.STALE_STREAM, staleStop.status)
        assertEquals(0, controller.stopCount)

        val stopped = coordinator.handleStopRequest(
            StopStreamRequestDto(2, SenderControlActionDto.STOP, streamId, receiverId, token),
            PEER_ADDRESS,
        )
        assertEquals(StopStreamStatusDto.STOPPED, stopped.status)
        assertEquals(1, controller.stopCount)

        val repeated = coordinator.handleStopRequest(
            StopStreamRequestDto(2, SenderControlActionDto.STOP, streamId, receiverId, token),
            PEER_ADDRESS,
        )
        assertEquals(StopStreamStatusDto.ALREADY_STOPPED, repeated.status)
        assertEquals(1, controller.stopCount)
        pairings.forgetAll()
        scope.cancel()
    }

    private class FakeSessionController : StreamSessionController {
        private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
        var stopCalled = false
        var startCount = 0
        var stopCount = 0

        override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

        override suspend fun start(
            endpoint: ReceiverEndpoint,
            preference: CodecPreference,
            profile: dev.mobilewebcam.sender.model.VideoProfile,
        ): Result<Unit> {
            startCount += 1
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCalled = true
            stopCount += 1
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

    private companion object {
        const val PEER_ADDRESS = "127.0.0.1"
    }
}
