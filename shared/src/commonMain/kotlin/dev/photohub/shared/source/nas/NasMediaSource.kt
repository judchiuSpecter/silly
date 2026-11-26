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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
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
 * copy is considered complete. Listing and image thumbnails come from QTS's
 * File Station HTTP API: compact paged JSON for the 96k archive and small
 * server-rendered JPEGs, so refresh no longer holds an SSH socket open for
 * minutes. SSH remains only for originals and archive writes. There is no
 * cursor: the whole page-walk is repeated on every refresh.
 *
 * QTS ships a self-signed host key by default, so verification is intentionally
 * promiscuous here rather than pinned — the user already decided to trust this
 * host by typing its address in. This mirrors trusting a NAS's self-signed TLS
 * cert, which the WebDAV alternative would have needed anyway.
 */
class NasMediaSource(
    override val config: SourceConfig,
    private val credentials: () -> String?,
    private val fileStation: FileStationApi? = null,
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
            val api = fileStation ?: error("NAS File Station API is not configured.")
            val root = settings.remotePath
                .removePrefix("/share")
                .ifBlank { "/" }
                .trimEnd('/')
                .ifBlank { "/" }
            val items = walkFileStation(api, root)
            MediaPage(items.sortedByDescending { it.dateTakenEpochMs }, null)
        }
    }

    /**
     * Walks the archive in small File Station pages. The gateway only forwards
     * HTTPS 443 now, so listing must not depend on a long-lived SSH socket.
     * Up to [WALK_CONCURRENCY] directories are paged at once — enough to finish
     * ~1,000 directories quickly without hammering the NAS or the phone.
     */
    private suspend fun walkFileStation(api: FileStationApi, root: String): List<RemoteMedia> =
        coroutineScope {
            val semaphore = Semaphore(WALK_CONCURRENCY)

            // Only the HTTP page requests take a permit. Holding one across the
            // recursive child join deadlocks once the tree is deeper than the
            // permit count: every ancestor waits for children that cannot start.
            suspend fun pageDirectory(path: String): Pair<List<RemoteMedia>, List<String>> =
                semaphore.withPermit {
                    val output = mutableListOf<RemoteMedia>()
                    val folders = mutableListOf<String>()
                    var start = 0
                    while (true) {
                        val page = api.list(path, start, LIST_PAGE_SIZE)
                        val entries = page.datas
                        for (entry in entries) {
                            val name = entry.filename
                            if (name.isBlank() || name.startsWith(".") || name in SKIPPED_DIR_NAMES) continue
                            if (entry.isfolder == 1) {
                                folders += "$path/$name"
                                continue
                            }
                            val mime = MediaTypes.mimeTypeOf(name) ?: continue
                            output += RemoteMedia(
                                remoteId = "/share$path/$name",
                                displayName = name,
                                mimeType = mime,
                                isVideo = MediaTypes.isVideoMime(mime),
                                sizeBytes = entry.filesize.toLongOrNull() ?: 0L,
                                dateTakenEpochMs = entry.epochmt * 1000L,
                                thumbnailUrl = null,
                            )
                        }
                        start += entries.size
                        if (entries.isEmpty() || start >= page.total) break
                    }
                    output to folders
                }

            suspend fun walk(path: String): List<RemoteMedia> {
                val (items, folders) = pageDirectory(path)
                val children = folders.map { folder -> async(Dispatchers.IO) { walk(folder) } }
                return items + children.awaitAll().flatten()
            }

            walk(root)
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
                        // QTS can render a small JPEG server-side. That avoids
                        // pulling a multi-megabyte original over SFTP for every
                        // visible cell. Videos get a blank placeholder, so they
                        // fall through to the local ffmpeg renderer.
                        if (!fetchViaFileStation(item, target)) {
                            downloadThumbnail(item, raw)
                            if (!renderThumbnail(raw.toString(), target, THUMB_EDGE)) {
                                error("Could not render a NAS thumbnail for ${item.displayName}")
                            }
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

    /** Writes a server-rendered File Station JPEG into the local thumbnail cache. */
    private suspend fun fetchViaFileStation(item: RemoteMedia, target: Path): Boolean {
        val api = fileStation ?: return false
        val folder = item.remoteId.substringBeforeLast('/')
        val name = item.remoteId.substringAfterLast('/')
        if (folder.isBlank() || name.isBlank()) return false
        // File Station paths start at the share root, while remote ids are
        // absolute SFTP paths like `/share/Photos/2024/clip.mp4`.
        val sharePath = if (folder.startsWith("/share")) folder.removePrefix("/share") else folder
        val bytes = api.thumbnail(sharePath, name) ?: return false
        target.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(target) { write(bytes) }
        return true
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
            // A dead host fails at TCP connect in 5s; the SSH handshake gets a
            // slightly longer socket timeout. Cancelling a coroutine here used
            // to close the transport mid-handshake and surface as "Software
            // caused connection abort", so bound the socket instead. Original
            // downloads afterwards restore an unlimited read timeout because
            // video files are much larger than a metadata request.
            ssh.setConnectTimeout(NAS_CONNECT_TIMEOUT_MS.toInt())
            ssh.setTimeout(NAS_HANDSHAKE_TIMEOUT_MS.toInt())
            val closeOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { ssh.disconnect() }
            }
            try {
                ssh.connect(settings.host, settings.port)
                ssh.authPassword(settings.username, password)
                ssh.setTimeout(0)
                block(ssh)
            } finally {
                closeOnCancel?.dispose()
                runCatching { ssh.disconnect() }
            }
        }

    private companion object {
        const val THUMB_EDGE = 384
        const val NAS_CONNECT_TIMEOUT_MS = 5_000L
        const val NAS_HANDSHAKE_TIMEOUT_MS = 15_000L
        const val NAS_THUMB_TIMEOUT_MS = 30_000L
        const val LIST_PAGE_SIZE = 2_000
        const val WALK_CONCURRENCY = 8
        const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024

        /** QTS system folders that live inside every share; never real photos. */
        val SKIPPED_DIR_NAMES = setOf("@Recycle", ".@__thumb", ".streams")
    }
}
