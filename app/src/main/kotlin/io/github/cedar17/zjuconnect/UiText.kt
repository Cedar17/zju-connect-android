package io.github.cedar17.zjuconnect

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A resource-backed user-facing string. */
internal sealed interface UiText {
    data class Resource(
        @get:StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText
}

@Composable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(
        id = id,
        *arguments.toTypedArray(),
    )
}
