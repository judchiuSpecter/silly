package dev.photohub.shared.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Back dismisses the viewer rather than leaving the app.
 *
 * Android has no arrow keys here, so the other callbacks go unused — swiping
 * is handled elsewhere in the viewer.
 */
@Composable
actual fun FullscreenDismissHandler(
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)
}
