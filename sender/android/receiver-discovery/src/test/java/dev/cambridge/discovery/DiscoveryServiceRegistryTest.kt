package dev.cambridge.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryServiceRegistryTest {
    @Test
    fun serviceUpdatePublishesEveryValidAdvertisedAddress() {
        val registry = DiscoveryServiceRegistry()

        registry.update(
            service(
                key = serviceKey("Studio OBS"),
                hosts = listOf("192.168.1.10", "100.64.0.10"),
            ),
        )

        assertEquals(
            setOf("192.168.1.10", "100.64.0.10"),
            registry.endpoints().map { endpoint -> endpoint.host }.toSet(),
        )
        assertTrue(registry.endpoints().all { endpoint -> endpoint.port == CONTROL_PORT })
    }

    @Test
    fun serviceUpdateReplacesAddressesFromThePreviousResolution() {
        val registry = DiscoveryServiceRegistry()
        val key = serviceKey("Studio OBS")
        registry.update(service(key, listOf("192.168.1.10")))

        registry.update(service(key, listOf("100.64.0.10")))

        assertEquals(listOf("100.64.0.10"), registry.endpoints().map { endpoint -> endpoint.host })
    }

    @Test
    fun serviceLossRemovesOnlyEndpointsFromThatService() {
        val registry = DiscoveryServiceRegistry()
        val studio = serviceKey("Studio OBS")
        val office = serviceKey("Office OBS")
        registry.update(service(studio, listOf("192.168.1.10")))
        registry.update(service(office, listOf("192.168.1.11")))

        registry.remove(studio)

        assertEquals(listOf("Office OBS"), registry.endpoints().map { endpoint -> endpoint.serviceName })
    }

    @Test
    fun duplicateEndpointAdvertisementsAreCollapsed() {
        val registry = DiscoveryServiceRegistry()
        registry.update(service(serviceKey("Studio OBS"), listOf("192.168.1.10")))
        registry.update(service(serviceKey("Studio OBS alias"), listOf("192.168.1.10")))

        assertEquals(1, registry.endpoints().size)
    }

    private fun service(
        key: NsdServiceKey,
        hosts: List<String>,
    ) = ResolvedNsdService(
        key = key,
        port = CONTROL_PORT,
        hosts = hosts,
    )

    private fun serviceKey(name: String) = NsdServiceKey(
        serviceName = name,
        serviceType = SERVICE_TYPE,
        networkHandle = null,
    )

    private companion object {
        const val CONTROL_PORT = 55_031
        const val SERVICE_TYPE = "_cambridge._tcp"
    }
}
