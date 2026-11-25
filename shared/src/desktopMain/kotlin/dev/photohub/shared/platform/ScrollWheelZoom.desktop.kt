package dev.photohub.shared.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Each wheel "tick" reports a `scrollDelta.y` of roughly 1f; scaling it this
 * way keeps a single notch close to a 10% zoom step, which felt right in
 * testing without jumping wildly on faster/high-resolution wheels.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.scrollWheelZoom(enabled: Boolean, onZoom: (Float) -> Unit): Modifier =
    if (!enabled) this else onPointerEvent(PointerEventType.Scroll) { event ->
        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
        if (delta != 0f) onZoom(1f - delta * 0.1f)
    }
