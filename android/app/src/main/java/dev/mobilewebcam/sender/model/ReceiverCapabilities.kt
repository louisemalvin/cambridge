package dev.mobilewebcam.sender.model

data class ReceiverCapabilities(
    val receiverId: String,
    val displayName: String,
    val profileIds: List<String>,
    val maxLongEdge: Int,
    val maxShortEdge: Int,
) {
    init {
        require(receiverId.isNotBlank()) { "Receiver ID must not be blank" }
        require(displayName.isNotBlank()) { "Receiver display name must not be blank" }
        require(profileIds.isNotEmpty() && profileIds.all(String::isNotBlank)) {
            "Receiver profiles must contain at least one non-blank ID"
        }
        require(profileIds.toSet().size == profileIds.size) { "Receiver profile IDs must be unique" }
        require(maxLongEdge > 0 && maxShortEdge > 0) { "Receiver bounds must be positive" }
        require(maxLongEdge >= maxShortEdge) { "Receiver long edge must not be below its short edge" }
    }

    fun supports(profile: VideoProfile): Boolean = profile.id in profileIds &&
        maxOf(profile.width, profile.height) <= maxLongEdge &&
        minOf(profile.width, profile.height) <= maxShortEdge
}
