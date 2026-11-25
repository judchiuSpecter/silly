package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.model.PhotoMetadata
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.platform.isUnmeteredNetwork
import dev.photohub.shared.platform.readPhotoMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Everything the right-hand info panel shows for one timeline item. */
@Serializable
data class MediaInfo(
    val displayName: String,
    val sourceName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val dateTakenEpochMs: Long,
    val syncState: String,
    val remoteId: String,
    val contentHash: String?,
    val metadata: PhotoMetadata? = null,
    /** Why EXIF fields are missing, when there is a user-relevant reason. */
    val note: String? = null,
) {
    companion object {
        /** Details available straight from the index, with no file access or network. */
        fun from(item: MediaItem, sourceName: String): MediaInfo = MediaInfo(
            displayName = item.displayName,
            sourceName = sourceName,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
            width = item.width?.toInt(),
            height = item.height?.toInt(),
            dateTakenEpochMs = item.dateTaken,
            syncState = item.syncState,
            remoteId = item.remoteId,
            contentHash = item.contentHash,
        )
    }
}

/**
 * Builds the data behind the photo-info panel.
 *
 * Index fields are always available. EXIF is read lazily: first from a cached
 * original, then from the local file/content URI, and only then — when the
 * device is on an unmetered connection — by asking [ensureOriginal] to fetch a
 * remote original. The result is stored back on the row, so the info panel is
 * instant after the first open and works offline afterwards.
 */
class MediaInfoController(
    private val repository: MediaRepository,
    private val readMetadata: suspend (String, String) -> PhotoMetadata? = ::readPhotoMetadata,
    private val ensureOriginal: suspend (MediaItem) -> String? = { null },
    private val networkAllowed: () -> Boolean = { isUnmeteredNetwork() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun info(item: MediaItem): MediaInfo {
        // Re-read the row: the first info open may have just written EXIF to it.
        val current = repository.itemById(item.id) ?: item
        val sourceName = repository.sourceById(current.sourceId)?.displayName ?: current.sourceId
        val base = MediaInfo.from(current, sourceName)

        current.metadataJson?.let { stored ->
            runCatching { json.decodeFromString<PhotoMetadata>(stored) }.getOrNull()
        }?.let { return base.copy(metadata = it) }

        val localRef = existingRef(current)
        if (localRef != null) {
            val metadata = readSafely(localRef, current.mimeType, current.id)
            return base.copy(
                metadata = metadata,
                note = if (metadata == null) "No camera metadata found in this file." else null,
            )
        }

        if (!networkAllowed()) {
            return base.copy(note = "Connect to Wi-Fi to read this photo's camera metadata.")
        }

        val downloaded = runCatching { ensureOriginal(current) }.getOrNull()
            ?: return base.copy(note = "Original is not cached; only indexed details are available.")

        val metadata = readSafely(downloaded, current.mimeType, current.id)
        return base.copy(
            metadata = metadata,
            note = if (metadata == null) "No camera metadata found in this file." else null,
        )
    }

    private suspend fun readSafely(ref: String, mimeType: String, mediaId: String): PhotoMetadata? {
        val metadata = runCatching { readMetadata(ref, mimeType) }.getOrNull() ?: return null
        runCatching { repository.setMetadata(json.encodeToString(metadata), mediaId) }
        return metadata
    }

    /**
     * A ref that can be read without a network request: a downloaded original,
     * an Android content URI, or a desktop absolute path. Cloud rows have none
     * until their original has been opened at least once.
     */
    private fun existingRef(item: MediaItem): String? {
        item.originalCachePath?.takeIf { it.isNotBlank() && File(it).exists() }?.let { return it }

        val config = repository.sourceById(item.sourceId)
        if (config?.type == SourceType.LOCAL) {
            item.remoteThumbUrl?.takeIf { it.startsWith("content://") }?.let { return it }
            item.remoteId.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
