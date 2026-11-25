package dev.photohub.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.MediaInfo
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Right-hand slide-over used from both the tile grid and the fullscreen viewer.
 *
 * Deliberately a panel rather than a dialog: on a phone it covers most of the
 * width, on desktop it leaves the photo visible. The scrim and the Close button
 * both dismiss it; back/Escape are handled by the App (panel first, viewer second).
 */
@Composable
fun MediaInfoPanel(
    state: MediaInfoPanelState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismiss() },
        )

        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(min = 280.dp, max = 380.dp),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Photo info", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                when {
                    state.loading -> {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.width(24.dp))
                        }
                    }
                    state.error != null -> {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    state.info != null -> InfoRows(state.info)
                }
            }
        }
    }
}

data class MediaInfoPanelState(
    val loading: Boolean = false,
    val info: MediaInfo? = null,
    val error: String? = null,
)

@Composable
private fun InfoRows(info: MediaInfo) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        InfoRow("Name", info.displayName)
        InfoRow("Source", info.sourceName)
        InfoRow("Size", formatBytes(info.sizeBytes))
        val dimensions = if (info.width != null && info.height != null) {
            "${info.width} × ${info.height}"
        } else {
            null
        }
        InfoRow("Dimensions", dimensions)
        InfoRow(
            "Taken",
            runCatching {
                Instant.fromEpochMilliseconds(info.dateTakenEpochMs)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .toString()
            }.getOrNull(),
        )
        InfoRow("Status", info.syncState)
        InfoRow("Path / id", info.remoteId)

        val meta = info.metadata
        if (meta != null) {
            SectionTitle("Camera")
            InfoRow("Camera", listOfNotNull(meta.make, meta.model).joinToString(" ").ifBlank { null })
            InfoRow("Lens", meta.lens)
            InfoRow("ISO", meta.iso?.toString())
            InfoRow("Aperture", meta.aperture?.let { "f/$it" })
            InfoRow("Shutter", meta.shutter)
            InfoRow("Focal length", meta.focalLength?.let { "${it}mm" })
            InfoRow("Original date", meta.originalDate)
            InfoRow("Software", meta.software)
            InfoRow("Orientation", meta.orientation?.toString())

            val location = if (meta.latitude != null && meta.longitude != null) {
                "${meta.latitude}, ${meta.longitude}"
            } else {
                null
            }
            if (location != null) {
                SectionTitle("Location")
                InfoRow("GPS", location)
            }
        }

        info.note?.let { note ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        info.contentHash?.let { hash ->
            Spacer(Modifier.height(12.dp))
            InfoRow("SHA-256", hash.take(16) + "…")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
