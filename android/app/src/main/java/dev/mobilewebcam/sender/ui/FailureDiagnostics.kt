package dev.mobilewebcam.sender.ui

import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.ui.components.failureMessage

internal fun buildFailureDiagnostics(
    state: SenderUiState,
    failure: StreamFailure,
    cause: Throwable?,
): String = buildString {
    appendLine("Mobile Webcam Android diagnostic")
    appendLine("User message: ${failureMessage(failure)}")
    appendLine("Receiver: ${state.receiverHost}:${state.controlPort}")
    appendLine(
        "Profile: ${state.profile.width}x${state.profile.height}@${state.profile.fps}",
    )
    appendLine("Codec preference: ${state.codecPreference}")
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
