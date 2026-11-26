package dev.photohub.shared.data

import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.drive.DriveSourceConfig
import dev.photohub.shared.source.mega.MegaSourceConfig
import dev.photohub.shared.source.nas.NasSourceConfig
import kotlinx.serialization.json.Json

/**
 * Stable cache key for a thumbnail, independent of the per-install [SourceConfig.id].
 *
 * The row id is `"<sourceId>:<remoteId>"`, so deleting an entry and adding the
 * same account/NAS again changes every row id and strands the cached JPEGs.
 * Deriving the key from the account's stable identity plus the remote object id
 * lets a newly connected source reuse thumbnails already on disk. Size (and NAS
 * mtime) are included so a replaced file does not inherit a stale thumbnail.
 */
object ThumbnailKeys {
    private val json = Json { ignoreUnknownKeys = true }

    fun forItem(config: SourceConfig, item: RemoteMedia): String {
        val identity = when (config.type) {
            SourceType.LOCAL -> "local"
            SourceType.GOOGLE_DRIVE -> {
                val drive = runCatching { json.decodeFromString<DriveSourceConfig>(config.configJson) }.getOrNull()
                "drive:${drive?.accountEmail.orEmpty().lowercase()}"
            }
            SourceType.MEGA -> {
                val mega = runCatching { json.decodeFromString<MegaSourceConfig>(config.configJson) }.getOrNull()
                "mega:${mega?.accountEmail.orEmpty().lowercase()}"
            }
            SourceType.NAS_ARCHIVE -> {
                val nas = runCatching { json.decodeFromString<NasSourceConfig>(config.configJson) }.getOrNull()
                    ?: NasSourceConfig()
                "nas:${nas.host.lowercase()}:${nas.port}:${nas.username.lowercase()}:${nas.remotePath}"
            }
            // Not implemented yet; keep the old per-instance behaviour.
            SourceType.ONEDRIVE, SourceType.TAKEOUT -> config.id
        }
        val freshness = if (config.type == SourceType.NAS_ARCHIVE) {
            "${item.sizeBytes}:${item.dateTakenEpochMs}"
        } else {
            item.sizeBytes.toString()
        }
        return "$identity|${item.remoteId}|$freshness"
    }
}
