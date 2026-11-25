package dev.photohub.shared.platform

import androidx.compose.ui.Modifier

/**
 * Desktop-only mouse-wheel zoom for the fullscreen viewer.
 *
 * JetBrains Compose Multiplatform's `onPointerEvent(PointerEventType.Scroll)`
 * has no counterpart in real AndroidX Compose — the Android target compiles
 * against Google's library, which lacks it — so this stays a platform seam.
 * Android relies on pinch-to-zoom instead and leaves this a no-op.
 *
 * [onZoom] receives a multiplicative factor (>1 to zoom in, <1 to zoom out)
 * for each wheel tick.
 */
expect fun Modifier.scrollWheelZoom(enabled: Boolean, onZoom: (Float) -> Unit): Modifier
