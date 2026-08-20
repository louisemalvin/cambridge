package dev.cambridge.sender.app.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object CameraPermission : AppDestination

    @Serializable
    data object StreamSetup : AppDestination

    @Serializable
    data object Webcam : AppDestination

    @Serializable
    data object Settings : AppDestination
}
