package dev.mobilewebcam.sender.model

enum class ReceiverDemand {
    INACTIVE,
    ACTIVE,
}

data class ReceiverDemandEvent(
    val generation: Long,
    val demand: ReceiverDemand,
    val consumerCount: Int,
)
