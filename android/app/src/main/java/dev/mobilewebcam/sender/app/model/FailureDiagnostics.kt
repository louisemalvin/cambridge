package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.VideoProfile

internal fun buildFailureDiagnostics(
    receiverName: String?,
    profile: VideoProfile,
    failure: StreamFailure,
    cause: Throwable?,
): String = buildString {
    appendLine("CamBridge Android diagnostic")
    appendLine("User message: ${StreamPresentationMapper.failureMessage(failure)}")
    appendLine("Receiver: ${receiverName ?: "not connected"}")
    appendLine(
        "Profile: ${profile.width}x${profile.height}@${profile.fps}",
    )
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
