package dev.cambridge.sender.media.camera

class CameraPermissionRequiredException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
