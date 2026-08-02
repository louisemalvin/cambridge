package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination

sealed interface StartupState {
    data object Resolving : StartupState
    data class Resolved(val initialDestination: AppDestination) : StartupState
}
