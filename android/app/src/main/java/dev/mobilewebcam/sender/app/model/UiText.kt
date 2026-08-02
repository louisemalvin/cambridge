package dev.mobilewebcam.sender.app.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data class Plain(val value: String) : UiText

    data class Resource(
        @param:StringRes val resourceId: Int,
        val formatArguments: List<Any> = emptyList(),
    ) : UiText
}

@Composable
fun UiText.Content() {
    androidx.compose.material3.Text(
        text = when (this) {
            is UiText.Plain -> value
            is UiText.Resource -> stringResource(resourceId, *formatArguments.toTypedArray())
        },
    )
}

@Composable
fun UiText.value(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Resource -> stringResource(resourceId, *formatArguments.toTypedArray())
}
