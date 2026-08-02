package dev.mobilewebcam.sender.app.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object Pairing : AppDestination

    @Serializable
    data object Webcam : AppDestination

    @Serializable
    data object Settings : AppDestination
}
