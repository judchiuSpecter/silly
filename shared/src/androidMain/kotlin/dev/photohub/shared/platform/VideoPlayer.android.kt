package dev.photohub.shared.platform

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * ExoPlayer embedded through [AndroidView]. [model] is the item's
 * `content://` URI (or, for a cached remote original, a plain file path).
 *
 * The player is built fresh per composition and released in
 * [DisposableEffect] so navigating to the next item in the viewer — which
 * recomposes with a new [model] — never leaves a decoder running in the
 * background.
 */
@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(model: Any, modifier: Modifier) {
    val context = LocalContext.current
    val uri = remember(model) {
        when (model) {
            is String -> if (model.startsWith("content://") || model.startsWith("https://")) {
                Uri.parse(model)
            } else {
                Uri.fromFile(java.io.File(model))
            }
            is java.io.File -> Uri.fromFile(model)
            else -> Uri.parse(model.toString())
        }
    }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                this.player = player
            }
        },
    )
}
