package dev.cambridge.discovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidReceiverDiscoveryTest {
    @Test
    fun startIsIdempotentAndPublishesAllResolvedAddresses() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = discovery(backend)

        discovery.start()
        discovery.start()
        backend.reportStarted()
        backend.reportService(
            service(
                serviceKey = STUDIO_SERVICE,
                hosts = listOf(LAN_HOST, VPN_HOST),
            ),
        )

        assertEquals(1, backend.startCount)
        assertEquals(ReceiverDiscoveryPhase.RUNNING, discovery.snapshot.value.phase)
        assertEquals(
            setOf(LAN_HOST, VPN_HOST),
            discovery.snapshot.value.endpoints.map { endpoint -> endpoint.host }.toSet(),
        )
    }

    @Test
    fun serviceLossAndStopRemoveStaleEndpoints() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = discovery(backend)
        discovery.start()
        backend.reportStarted()
        backend.reportService(service(STUDIO_SERVICE, listOf(LAN_HOST)))

        backend.reportLost(STUDIO_SERVICE)
        assertEquals(emptyList<DiscoveredReceiverEndpoint>(), discovery.snapshot.value.endpoints)

        backend.reportService(service(STUDIO_SERVICE, listOf(LAN_HOST)))
        discovery.stop()
        discovery.stop()

        assertEquals(1, backend.stopCount)
        assertEquals(ReceiverDiscoverySnapshot.Stopped, discovery.snapshot.value)
    }

    @Test
    fun fatalFailureIsVisibleAndRestartsAfterTheContractDelay() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = discovery(backend)
        discovery.start()

        backend.reportFailure(
            NsdBackendFailure(
                operation = ReceiverDiscoveryOperation.START_DISCOVERY,
                errorCode = FAILURE_CODE,
                message = "temporary failure",
                isFatal = true,
            ),
        )

        assertEquals(ReceiverDiscoveryPhase.RETRY_WAIT, discovery.snapshot.value.phase)
        assertEquals(FAILURE_CODE, discovery.snapshot.value.failure?.errorCode)

        advanceTimeBy(RESTART_DELAY_MILLIS)
        runCurrent()

        assertEquals(2, backend.startCount)
        assertEquals(ReceiverDiscoveryPhase.STARTING, discovery.snapshot.value.phase)
    }

    @Test
    fun nonFatalResolutionFailureKeepsDiscoveryRunning() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = discovery(backend)
        discovery.start()
        backend.reportStarted()

        backend.reportFailure(
            NsdBackendFailure(
                operation = ReceiverDiscoveryOperation.RESOLVE_SERVICE,
                errorCode = FAILURE_CODE,
                message = "resolution failed",
                isFatal = false,
            ),
        )

        assertEquals(ReceiverDiscoveryPhase.RUNNING, discovery.snapshot.value.phase)
        assertEquals(ReceiverDiscoveryOperation.RESOLVE_SERVICE, discovery.snapshot.value.failure?.operation)
        assertEquals(1, backend.startCount)
    }

    private fun kotlinx.coroutines.test.TestScope.discovery(
        backend: FakeNsdDiscoveryBackend,
    ) = AndroidReceiverDiscovery(
        config = ReceiverDiscoveryConfig(
            serviceType = SERVICE_TYPE,
            restartDelayMillis = RESTART_DELAY_MILLIS,
        ),
        backend = backend,
        scope = backgroundScope,
    )

    private fun service(
        serviceKey: NsdServiceKey,
        hosts: List<String>,
    ) = ResolvedNsdService(
        key = serviceKey,
        port = CONTROL_PORT,
        hosts = hosts,
    )

    private class FakeNsdDiscoveryBackend : NsdDiscoveryBackend {
        var startCount = 0
        var stopCount = 0
        private var listener: NsdDiscoveryBackend.Listener? = null

        override fun start(listener: NsdDiscoveryBackend.Listener) {
            startCount += 1
            this.listener = listener
        }

        override fun stop() {
            stopCount += 1
            listener = null
        }

        fun reportStarted() {
            listener?.onStarted()
        }

        fun reportService(service: ResolvedNsdService) {
            listener?.onServiceUpdated(service)
        }

        fun reportLost(serviceKey: NsdServiceKey) {
            listener?.onServiceLost(serviceKey)
        }

        fun reportFailure(failure: NsdBackendFailure) {
            listener?.onFailure(failure)
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_cambridge._tcp"
        const val CONTROL_PORT = 55_031
        const val RESTART_DELAY_MILLIS = 100L
        const val FAILURE_CODE = 3
        const val LAN_HOST = "192.168.1.10"
        const val VPN_HOST = "100.64.0.10"
        val STUDIO_SERVICE = NsdServiceKey(
            serviceName = "Studio OBS",
            serviceType = SERVICE_TYPE,
            networkHandle = null,
        )
    }
}
