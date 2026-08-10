package dev.cambridge.sender.model

data class ReceiverCandidate(
    val endpoint: ReceiverEndpoint,
    val capabilities: ReceiverCapabilities,
) {
    init {
        require(endpoint.isValid()) { "Receiver candidate endpoint must be valid" }
        require(endpoint.receiverId == capabilities.receiverId) {
            "Receiver candidate endpoint and capabilities IDs must match"
        }
    }
}
