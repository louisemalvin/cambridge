package dev.cambridge.sender.app.startup

import dev.cambridge.sender.app.navigation.AppDestination

sealed interface StartupState {
    data object Resolving : StartupState
    data class Resolved(val initialDestination: AppDestination) : StartupState
}
