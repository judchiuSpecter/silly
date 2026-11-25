package dev.photohub.shared.platform

import androidx.compose.ui.Modifier

/** No mouse wheel on Android; pinch-to-zoom covers this instead. */
actual fun Modifier.scrollWheelZoom(enabled: Boolean, onZoom: (Float) -> Unit): Modifier = this
