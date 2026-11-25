package dev.photohub.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Inline video playback for the fullscreen viewer.
 *
 * [model] is whatever [dev.photohub.shared.ui.MediaModel.imageModel] resolved
 * to for this item: a `content://` URI string on Android, or an absolute file
 * path on desktop. Each platform ships its own decoders (ExoPlayer on
 * Android, JavaFX media on desktop), so no system media player install is
 * required.
 */
@Composable
expect fun VideoPlayer(model: Any, modifier: Modifier)
