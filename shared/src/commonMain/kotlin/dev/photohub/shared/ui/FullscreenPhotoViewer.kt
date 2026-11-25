package dev.photohub.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.platform.FullscreenDismissHandler
import dev.photohub.shared.platform.VideoPlayer
import dev.photohub.shared.platform.scrollWheelZoom
import java.io.File
import kotlin.math.abs

/**
 * Fullscreen photo/video viewer that replaces the timeline grid.
 *
 * - Local items load from their `content://` URI (Android) or file path (desktop).
 * - Cloud items start from their thumbnail URL and download the original through
 *   the owning source so the full image is shown once available.
 * - Videos play inline through [dev.photohub.shared.platform.VideoPlayer] once
 *   a local path/content URI is available (ExoPlayer on Android, JavaFX media
 *   on desktop); until then they show their static thumbnail with a ▶ mark.
 * - Dismissal is platform-native: device Back on Android, Escape on desktop.
 *   There is deliberately no on-screen close button. On touch screens, swipe
 *   left/right to step through [onPrevious]/[onNext], up to return to the tile
 *   grid, and down to delete. Desktop mirrors this with arrow keys.
 */
@Composable
fun FullscreenPhotoViewer(
    item: MediaItem,
    onDismiss: () -> Unit,
    images: FullscreenImageController? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onDelete: () -> Unit = {},
    onMove: () -> Unit = {},
    onInfo: () -> Unit = {},
    canMove: Boolean = false,
    busy: Boolean = false,
) {
    var imageModel by remember(item.id) { mutableStateOf(initialModel(item)) }
    var downloading by remember(item.id) { mutableStateOf(false) }

    // Pinch/scroll zoom and pan for still images; reset whenever the item
    // changes so the next photo always opens at a normal, unzoomed size.
    // Videos keep their own player controls and are never zoomed.
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }

    // A plain thumbnail URL (remote item whose original has not downloaded
    // yet) cannot be played directly; the viewer waits for ensureOriginal()
    // to swap imageModel to a local path/content URI, same as it does for
    // the full-res image.
    val isPlayableVideo = item.isVideoItem && imageModel != null &&
        (imageModel !is String || !(imageModel as String).startsWith("https://"))

    // Android Back / desktop Escape + arrow keys. Placed first so the
    // focusable desktop box sits behind the image and cannot intercept clicks.
    FullscreenDismissHandler(
        onDismiss = onDismiss,
        onPrevious = onPrevious,
        onNext = onNext,
        onDelete = onDelete,
    )

    // A cloud item starts with its thumbnail URL. Try to upgrade it to the
    // original through the source that owns this account; failure is non-fatal
    // because the thumbnail is already visible.
    LaunchedEffect(item.id) {
        val current = imageModel
        if (images == null || current !is String || !current.startsWith("https://")) return@LaunchedEffect
        downloading = true
        val original = images.ensureOriginal(item)
        if (original != null) imageModel = File(original)
        downloading = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Videos keep this simple — their own player controls already
                // handle interaction, and swipe-nav/delete stay as plain drags.
                // Stills route all gestures through one detector: a single
                // unzoomed drag accumulates into the swipe-nav/delete actions
                // below, while pinching (or any drag once zoomed) instead
                // zooms/pans the image, mirrored on desktop by scroll-to-zoom
                // and a mouse-held drag.
                .pointerInput(item.id, isPlayableVideo) {
                    if (isPlayableVideo) {
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = { totalX = 0f; totalY = 0f },
                            onDrag = { _, dragAmount -> totalX += dragAmount.x; totalY += dragAmount.y },
                            onDragEnd = {
                                when {
                                    abs(totalY) >= 96f && abs(totalY) > abs(totalX) * 1.2f -> {
                                        if (totalY > 0f) onDelete() else onDismiss()
                                    }
                                    abs(totalX) >= 72f -> {
                                        if (totalX < 0f) onNext() else onPrevious()
                                    }
                                }
                            },
                        )
                        return@pointerInput
                    }
                    while (true) {
                        var totalX = 0f
                        var totalY = 0f
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (scale > 1.01f || zoom != 1f) {
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                if (newScale <= 1.01f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            } else {
                                totalX += pan.x
                                totalY += pan.y
                            }
                        }
                        if (scale <= 1.01f) {
                            when {
                                // Vertical wins only when it is clearly the intended
                                // direction, so a slight diagonal on a photo swipe
                                // cannot delete or close anything.
                                abs(totalY) >= 96f && abs(totalY) > abs(totalX) * 1.2f -> {
                                    if (totalY > 0f) onDelete() else onDismiss()
                                }
                                abs(totalX) >= 72f -> {
                                    if (totalX < 0f) onNext() else onPrevious()
                                }
                            }
                        }
                    }
                }
                .scrollWheelZoom(enabled = !isPlayableVideo) { factor ->
                    val newScale = (scale * factor).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale <= 1.01f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isPlayableVideo) {
                    VideoPlayer(model = imageModel!!, modifier = Modifier.fillMaxSize())
                } else if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Image not available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Explicit destructive actions. No close button: dismissal stays native
        // (Back on Android, Escape on desktop) so a stray tap cannot exit.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            TextButton(
                onClick = onDelete,
                enabled = !busy,
                content = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            )
            TextButton(
                onClick = onMove,
                enabled = canMove && !busy,
                content = { Text("Move") },
            )
        }

        // The viewer always offers info.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(32.dp)
                .clickable { onInfo() },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "i",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (downloading) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text(
                    "  Loading full image…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Before the original downloads, a video shows only its static
        // thumbnail (VideoPlayer needs a local path/content URI); mark it so
        // that still frame is not mistaken for a photo. Once playable, the
        // player's own controls already make it obvious.
        if (item.isVideoItem && !isPlayableVideo) {
            Text(
                "▶",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-12).dp, y = (-12).dp),
            )
        }
    }
}

/** Resolves what can be shown without a network request. */
private fun initialModel(item: MediaItem): Any? {
    fun existingFile(path: String?): File? =
        path?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }

    // Content URIs must stay as strings so Coil's UriFetcher handles them.
    return item.remoteThumbUrl?.takeIf { it.startsWith("content://") }
        ?: existingFile(item.originalCachePath)
        ?: existingFile(item.thumbCachePath)
        ?: existingFile(item.remoteId)
        // A Drive thumbnailLink is a plain https URL. Coil loads it directly
        // (the grid does the same), so the image appears immediately while the
        // original is fetched in the background.
        ?: item.remoteThumbUrl?.takeIf { it.startsWith("https://") }
}
