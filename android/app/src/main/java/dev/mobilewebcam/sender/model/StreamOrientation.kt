package dev.mobilewebcam.sender.model

enum class StreamOrientation(
    val isPortrait: Boolean,
) {
    PORTRAIT(isPortrait = true),
    LANDSCAPE(isPortrait = false),
}
