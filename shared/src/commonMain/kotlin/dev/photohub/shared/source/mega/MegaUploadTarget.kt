package dev.photohub.shared.source.mega

import dev.carlsen.mega.model.Node
import dev.carlsen.mega.util.CancellationToken
import dev.photohub.shared.auth.MegaAccountFactory.Account
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.platform.displayName
import dev.photohub.shared.platform.fileSize
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.materializeLocalFile
import kotlinx.io.buffered
import kotlinx.io.files.Path as KxPath
import kotlinx.io.files.SystemFileSystem

/**
 * Adapts a MEGA account to the queue's [UploadTarget].
 *
 * Uploads are copies: the camera file is only read. Folders are created on
 * demand with `YYYY/MM` under PhotoHub, matching the Drive layout, and a
 * temporary copy is used when Android supplies a content URI instead of a path.
 */
class MegaUploadTarget(
    private val account: Account,
) : UploadTarget {

    override val sourceId: String get() = account.sourceId

    override suspend fun upload(
        local: String,
        remoteFolder: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String {
        val size = fileSize(local) ?: error("Cannot determine size of $local")
        val materialized = materializeLocalFile(local)
        try {
            val destination = ensureFolder(remoteFolder)
            val name = displayName(local)

            // A retry only reaches here after the previous attempt's exception
            // propagated as a failure — which also happens when MEGA received
            // and stored the file but the client lost the connection before
            // seeing confirmation. Without this check, that retry re-uploads
            // and leaves two identical files in the folder; the local queue
            // has no record of the first one succeeding, so its own dedupe
            // (by content hash) never catches it either. See the matching fix
            // in DriveUploader.upload().
            account.mega.getChildren(destination)
                .firstOrNull { it.isFile && it.name == name && it.size == size }
                ?.let { existing ->
                    onProgress(1f)
                    return existing.hash
                }

            val source = SystemFileSystem.source(KxPath(materialized.path.toString())).buffered()
            val node = account.mega.uploadFile(
                destNode = destination,
                name = displayName(local),
                fileSize = size,
                fileInputSource = source,
                cancellationToken = CancellationToken.default(),
                onProgress = { bytes, total ->
                    if (total > 0) onProgress((bytes.toFloat() / total).coerceIn(0f, 1f))
                },
            )
            return node.hash
        } finally {
            if (materialized.isTemporary) {
                runCatching { fileSystem.delete(materialized.path) }
            }
        }
    }

    /** Resolves `PhotoHub/YYYY/MM`, creating any missing folder along the way. */
    private suspend fun ensureFolder(path: String): Node {
        var parent = account.mega.getFileSystem().root ?: error("MEGA root is unavailable")
        for (part in path.split('/').filter { it.isNotBlank() }) {
            val existing = account.mega.getChildren(parent)
                .firstOrNull { it.isFolder && it.name == part }
            parent = existing ?: account.mega.createDir(part, parent)
        }
        return parent
    }
}
