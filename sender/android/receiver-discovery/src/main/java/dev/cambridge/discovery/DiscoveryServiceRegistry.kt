package dev.cambridge.discovery

internal class DiscoveryServiceRegistry {
    private val endpointsByService = linkedMapOf<NsdServiceKey, List<DiscoveredReceiverEndpoint>>()

    fun update(service: ResolvedNsdService) {
        endpointsByService[service.key] = service.hosts.map { host ->
            DiscoveredReceiverEndpoint(
                serviceName = service.key.serviceName,
                host = host,
                port = service.port,
            )
        }.filter(DiscoveredReceiverEndpoint::isValid)
            .distinctBy { endpoint -> endpoint.host to endpoint.port }
    }

    fun remove(serviceKey: NsdServiceKey) {
        endpointsByService.remove(serviceKey)
    }

    fun clear() {
        endpointsByService.clear()
    }

    fun endpoints(): List<DiscoveredReceiverEndpoint> = endpointsByService.values
        .flatten()
        .distinctBy { endpoint -> endpoint.host to endpoint.port }
        .sortedWith(
            compareBy<DiscoveredReceiverEndpoint> { endpoint -> endpoint.serviceName }
                .thenBy { endpoint -> endpoint.host }
                .thenBy { endpoint -> endpoint.port },
        )
}
