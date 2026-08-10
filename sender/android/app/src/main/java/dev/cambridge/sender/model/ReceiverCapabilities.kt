package dev.cambridge.sender.model

data class ReceiverCapabilities(
    val receiverId: String,
    val displayName: String,
    val maxLongEdge: Int,
    val maxShortEdge: Int,
) {
    init {
        require(receiverId.isNotBlank()) { "Receiver ID must not be blank" }
        require(displayName.isNotBlank()) { "Receiver display name must not be blank" }
        require(maxLongEdge > 0 && maxShortEdge > 0) { "Receiver bounds must be positive" }
        require(maxLongEdge >= maxShortEdge) { "Receiver long edge must not be below its short edge" }
    }

    fun supportsGeometry(width: Int, height: Int): Boolean =
        maxOf(width, height) <= maxLongEdge && minOf(width, height) <= maxShortEdge
}
