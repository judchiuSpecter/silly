package dev.photohub.shared.source.nas

import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.renderThumbnail
import dev.photohub.shared.source.MediaPage
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.MediaTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import okio.Path
import java.io.File

/**
 * NAS archive as a PhotoHub media source, reached over SFTP.
 *
 * Timeline sync stays read-only, but an explicitly chosen NAS archive target
 * can receive copies and delete its own copies when the user archives or deletes
 * a photo. Writes go through [NasArchiveWriter], which verifies bytes before a
 * copy is considered complete. QTS's own `.@__thumb` JPEGs are used for grid
 * thumbnails when present, falling back to the original. There is no cursor:
 * one `ls` walk of
 * [NasSourceConfig.remotePath] is cheap enough (recursive directory listing,
 * not a download) to do fully on every refresh.
 *
 * QTS ships a self-signed host key by default, so verification is intentionally
 * promiscuous here rather than pinned — the user already decided to trust this
 * host by typing its address in. This mirrors trusting a NAS's self-signed TLS
 * cert, which the WebDAV alternative would have needed anyway.
 */
class NasMediaSource(
    override val config: SourceConfig,
    private val credentials: () -> String?,
) : MediaSource {

    private val settings: NasSourceConfig =
        runCatching {
            kotlinx.serialization.json.Json.decodeFromString<NasSourceConfig>(config.configJson)
        }.getOrElse { NasSourceConfig() }

    private val writer = NasArchiveWriter(config.configJson, credentials)

    /** Writable only for explicit Archive actions, never for camera uploads. */
    override val canUpload: Boolean get() = true
    override val canDelete: Boolean get() = true

    override suspend fun list(cursor: String?): Result<MediaPage> = withContext(Dispatchers.IO) {
        runCatching {
            if (cursor != null) return@runCatching MediaPage(emptyList(), null)

            withClient { ssh ->
                ssh.newSFTPClient().use { sftp ->
                    val items = mutableListOf<RemoteMedia>()
                    val root = settings.remotePath.ifBlank { "/" }

                    fun walk(path: String) {
                        val entries = sftp.ls(path)
                        for (entry in entries) {
                            if (entry.name == "." || entry.name == "..") continue
                            // QTS keeps a hidden recycle bin and a thumbnail
                            // cache beside every share; neither belongs in the
                            // timeline as media, though fetchThumbnail reads the
                            // latter instead of downloading full originals.
                            if (entry.name in SKIPPED_DIR_NAMES) continue
                            when {
                                entry.isDirectory -> walk(entry.path)
                                entry.isRegularFile -> {
                                    val mime = MediaTypes.mimeTypeOf(entry.name) ?: continue
                                    items += entry.toRemoteMedia(mime)
                                }
                            }
                        }
                    }
                    walk(root)

                    MediaPage(items.sortedByDescending { it.dateTakenEpochMs }, null)
                }
            }
        }
    }

    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(NAS_THUMB_TIMEOUT_MS) {
                    if (item.isVideo && item.sizeBytes > MAX_VIDEO_THUMB_BYTES) {
                        error("Video ${item.displayName} is too large to thumbnail")
                    }
                    target.parent?.let { fileSystem.createDirectories(it) }
                    val raw = target.parent?.resolve("${target.name}.original")
                        ?: error("Thumbnail path has no parent directory")

                    try {
                        downloadThumbnail(item, raw)
                        if (!renderThumbnail(raw.toString(), target, THUMB_EDGE)) {
                            error("Could not render a NAS thumbnail for ${item.displayName}")
                        }
                        target
                    } finally {
                        runCatching { fileSystem.delete(raw) }
                    }
                }
            }
        }

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> = withContext(Dispatchers.IO) {
        runCatching {
            target.parent?.let { fileSystem.createDirectories(it) }
            downloadTo(item.remoteId, target)
            // sshj's file transfer has no incremental progress callback used
            // here, so this reports completion rather than real-time percent.
            onProgress(1f)
            target
        }
    }

    /**
     * Copies a local file under the configured share root, verifying the bytes
     * read back from the NAS before returning. [remoteFolder] is relative to
     * that root so the caller cannot write outside the indexed share.
     */
    override suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit,
    ): Result<RemoteMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val remotePath = remotePathInShare(remoteFolder, local.name)
            writer.copyVerified(local, remotePath).getOrThrow()
            onProgress(1f)
            val meta = runCatching { fileSystem.metadata(local) }.getOrNull()
            val mime = MediaTypes.mimeTypeOf(local.name) ?: "application/octet-stream"
            RemoteMedia(
                remoteId = remotePath,
                displayName = local.name,
                mimeType = mime,
                isVideo = MediaTypes.isVideoMime(mime),
                sizeBytes = meta?.size ?: 0L,
                dateTakenEpochMs = meta?.lastModifiedAtMillis ?: 0L,
            )
        }
    }

    /** Deletes only a file we listed under the configured share root. */
    override suspend fun delete(item: RemoteMedia): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = settings.remotePath.ifBlank { "/" }.trimEnd('/')
            val prefix = if (root.isEmpty() || root == "/") "/" else "$root/"
            require(item.remoteId.startsWith(prefix)) {
                "Refusing to delete a NAS file outside the configured folder"
            }
            withClient { ssh ->
                ssh.newSFTPClient().use { sftp -> sftp.rm(item.remoteId) }
            }
        }
    }

    private fun remotePathInShare(folder: String, name: String): String {
        val root = settings.remotePath.ifBlank { "/" }.trimEnd('/')
        val segments = folder.split('/').map { it.trim() }.filter {
            it.isNotBlank() && it != "." && it != ".."
        }
        require(segments.isNotEmpty()) { "Archive folder must not be empty" }
        val safeName = name.map { c ->
            if (c.isLetterOrDigit() || c in ".-_ ") c else '_'
        }.joinToString("").ifBlank { "photo" }
        return if (root.isEmpty() || root == "/") {
            "/" + (segments + safeName).joinToString("/")
        } else {
            "$root/${(segments + safeName).joinToString("/")}"
        }
    }

    private suspend fun downloadTo(remotePath: String, target: Path) {
        withClient { ssh ->
            ssh.newSFTPClient().use { sftp ->
                sftp.get(remotePath, FileSystemFile(File(target.toString())))
            }
        }
    }

    /**
     * Prefers QTS's pre-rendered JPEG over the original. A full NAS can hold
     * 100k+ files; pulling a 30KB server thumbnail instead of a multi-MB photo
     * is the difference between a usable timeline and a long SFTP transfer.
     * If the NAS has no cached thumb, fall back to the original in one pass.
     */
    private suspend fun downloadThumbnail(item: RemoteMedia, target: Path) {
        val candidates = serverThumbnailPaths(item.remoteId)
        withClient { ssh ->
            ssh.newSFTPClient().use { sftp ->
                val serverThumb = candidates.firstOrNull { path ->
                    runCatching { sftp.stat(path) != null }.getOrDefault(false)
                }
                sftp.get(
                    serverThumb ?: item.remoteId,
                    FileSystemFile(File(target.toString())),
                )
            }
        }
    }

    /** `default` is ~530px, `s800` ~1066px, `s100` ~130px; all use the source name. */
    private fun serverThumbnailPaths(remotePath: String): List<String> {
        val slash = remotePath.lastIndexOf('/')
        val parent = if (slash <= 0) "" else remotePath.substring(0, slash)
        val name = remotePath.substring(slash + 1)
        val thumbDir = when (parent) {
            "", "/" -> "/.@__thumb"
            else -> "$parent/.@__thumb"
        }
        return listOf("default", "s800", "s100").map { "$thumbDir/$it$name" }
    }

    /**
     * Opens one short-lived connection per call; the NAS is only occasionally
     * reachable. Closing the transport on cancellation is essential: sshj's
     * SFTP reads are blocking, so without this a timed-out thumbnail would keep
     * an IO dispatcher thread parked on the socket until the read finished.
     */
    private suspend fun <T> withClient(block: (SSHClient) -> T): T =
        withContext(Dispatchers.IO) {
            val password = credentials() ?: error("Not connected to the NAS.")
            val ssh = newNasSshClient()
            // QTS's default host key cannot be pinned without the user copying a
            // fingerprint off the NAS; the connection is already opted into by
            // entering its address, same trust model as its self-signed WebDAV cert.
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            val closeOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { ssh.disconnect() }
            }
            try {
                ssh.connect(settings.host, settings.port)
                ssh.authPassword(settings.username, password)
                block(ssh)
            } finally {
                closeOnCancel?.dispose()
                runCatching { ssh.disconnect() }
            }
        }

    private fun RemoteResourceInfo.toRemoteMedia(mime: String): RemoteMedia {
        val attrs = attributes
        return RemoteMedia(
            remoteId = path,
            displayName = name,
            mimeType = mime,
            isVideo = MediaTypes.isVideoMime(mime),
            sizeBytes = attrs.size,
            // SFTP only reports mtime; good enough for sort order on an archive
            // where files are not edited after being moved there.
            dateTakenEpochMs = attrs.mtime * 1000L,
            // QTS thumbnails are files beside the original, not a URL the UI
            // can load; fetchThumbnail copies one into our own cache.
            thumbnailUrl = null,
        )
    }

    private companion object {
        const val THUMB_EDGE = 384
        const val NAS_THUMB_TIMEOUT_MS = 30_000L
        const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024

        /** QTS system folders that live inside every share; never real photos. */
        val SKIPPED_DIR_NAMES = setOf("@Recycle", ".@__thumb", ".streams")
    }
}
