package dev.mobilewebcam.sender.control

sealed interface ReceiverControlError {
    data class Network(val reason: String) : ReceiverControlError
    data class Protocol(val reason: String) : ReceiverControlError
    data class Rejected(val statusCode: Int, val reason: String) : ReceiverControlError
    data class Unexpected(val cause: Throwable) : ReceiverControlError
}

class ReceiverControlException(
    val error: ReceiverControlError,
    cause: Throwable? = null,
) : IllegalStateException(error.toString(), cause)
