package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.VideoProfile

internal fun buildFailureDiagnostics(
    receiverName: String?,
    profile: VideoProfile,
    codecPreference: CodecPreference,
    failure: StreamFailure,
    cause: Throwable?,
): String = buildString {
    appendLine("Mobile Webcam Android diagnostic")
    appendLine("User message: ${SenderScreenStateMapper.failureMessage(failure)}")
    appendLine("Receiver: ${receiverName ?: "not connected"}")
    appendLine(
        "Profile: ${profile.width}x${profile.height}@${profile.fps}",
    )
    appendLine("Codec preference: $codecPreference")
    appendLine("Failure type: ${failure::class.qualifiedName}")
    cause?.let {
        appendLine("Exception details:")
        appendThrowable(it)
    }
}

private fun StringBuilder.appendThrowable(error: Throwable) {
    var current: Throwable? = error
    var first = true
    while (current != null) {
        if (!first) {
            appendLine("Caused by:")
        }
        appendLine("${current::class.qualifiedName}: ${current.message.orEmpty()}")
        current.stackTrace.forEach { frame -> appendLine("    at $frame") }
        current = current.cause
        first = false
    }
}
