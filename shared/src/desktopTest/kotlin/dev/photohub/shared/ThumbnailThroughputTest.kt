package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.ThumbnailStore
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.platform.thumbnailConcurrency
import dev.photohub.shared.source.LocalDirectorySource
import dev.photohub.shared.source.MediaPage
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.drive.DriveSourceConfig
import dev.photohub.shared.source.nas.NasSourceConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Generates a synthetic library of large photos and times a full thumbnail pass,
 * to confirm parallel rendering actually scales and stays correct under load.
 */
class ThumbnailThroughputTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    @Test
    fun generatesThumbnailsForEveryItemConcurrently() = runTest(timeout = kotlin.time.Duration.INFINITE) {
        val count = 48
        val root = Files.createTempDirectory("thumb-bench").toFile()

        // 3000x2000 originals, in the range of a real camera roll.
        val base = BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB).also { img ->
            for (x in 0 until 3000 step 3) {
                for (y in 0 until 2000 step 3) img.setRGB(x, y, (x * 31 + y * 17) or 0x101010)
            }
        }
        repeat(count) { i -> ImageIO.write(base, "jpg", File(root, "photo-$i.jpg")) }

        val db = newDb()
        val repo = MediaRepository(db)
        repo.ensureLocalSource()

        val config = SourceConfig(
            id = LocalSourceConfig.SOURCE_ID,
            type = SourceType.LOCAL,
            displayName = "This device",
            enabled = true,
            isUploadTarget = false,
            configJson = Json.encodeToString(
                LocalSourceConfig(roots = listOf(root.absolutePath)),
            ),
        )

        MediaIndexer(db).index(LocalDirectorySource(config)).getOrThrow()
        assertEquals(count.toLong(), repo.totalCount())

        var progressCalls = 0
        val store = ThumbnailStore(db)
        val elapsed = measureTime {
            val stats = store.generateMissing(onProgress = { progressCalls++ })
            assertEquals(count, stats.generated)
            assertEquals(0, stats.failed)
        }

        val perItem = elapsed.inWholeMilliseconds.toDouble() / count
        println("[bench] concurrency=$thumbnailConcurrency items=$count")
        println("[bench] total=${elapsed.inWholeMilliseconds}ms  per-item=${"%.1f".format(perItem)}ms")
        println("[bench] progress callbacks=$progressCalls")

        // Every row must now carry a thumbnail that exists on disk.
        val rows = repo.timelinePage(limit = 200, offset = 0)
        assertTrue(rows.all { it.thumbCachePath != null })
        assertTrue(rows.all { File(it.thumbCachePath!!).isFile })
        assertTrue(rows.all { it.syncState == "THUMB_CACHED" })

        // Progress must be reported in batches, not once at the end.
        assertTrue(progressCalls > 1, "expected incremental progress, got $progressCalls")

        // A second pass has nothing left to render.
        val second = store.generateMissing()
        assertEquals(0, second.completed)
    }

    @Test
    fun reusesCachedFilesWhenTheDbLinkIsLost() = runTest {
        val root = Files.createTempDirectory("thumb-reuse").toFile()
        val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        repeat(4) { i -> ImageIO.write(img, "jpg", File(root, "p-$i.jpg")) }

        val db = newDb()
        val repo = MediaRepository(db)
        repo.ensureLocalSource()
        val config = SourceConfig(
            id = LocalSourceConfig.SOURCE_ID,
            type = SourceType.LOCAL,
            displayName = "This device",
            enabled = true,
            isUploadTarget = false,
            configJson = Json.encodeToString(
                LocalSourceConfig(roots = listOf(root.absolutePath)),
            ),
        )
        MediaIndexer(db).index(LocalDirectorySource(config)).getOrThrow()

        val store = ThumbnailStore(db)
        assertEquals(4, store.generateMissing().generated)

        // Simulate losing the DB links (e.g. a reinstall) with the cache intact.
        repo.timelinePage(limit = 10, offset = 0).forEach {
            db.mediaItemQueries.clearThumbCache(it.id)
        }

        val stats = store.generateMissing()
        assertEquals(4, stats.reused)
        assertEquals(0, stats.generated)
    }

    @Test
    fun reusesThumbnailWhenTheSameNasIsAddedWithANewSourceId() = runTest {
        val configJson = Json.encodeToString(
            NasSourceConfig(host = "nas.local", username = "admin", remotePath = "/share/Photos"),
        )
        val item = RemoteMedia(
            remoteId = "/share/Photos/one.jpg",
            displayName = "one.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            sizeBytes = 1234,
            dateTakenEpochMs = 1000L,
        )
        val db = newDb()
        val repo = MediaRepository(db)

        repo.upsertCloudSource("nas_1", SourceType.NAS_ARCHIVE, "NAS", configJson)
        val first = CountingSource(nasConfig("nas_1", configJson), item)
        MediaIndexer(db).index(first).getOrThrow()
        val firstStats = ThumbnailStore(db).fetchRemote(first, limit = 10)
        assertEquals(1, firstStats.generated)
        assertEquals(1, first.fetchCalls)

        // Same NAS, new per-install id: the JPEG must be linked, not re-downloaded.
        repo.upsertCloudSource("nas_2", SourceType.NAS_ARCHIVE, "NAS", configJson)
        val second = CountingSource(nasConfig("nas_2", configJson), item)
        MediaIndexer(db).index(second).getOrThrow()
        val secondStats = ThumbnailStore(db).fetchRemote(second, limit = 10)
        assertEquals(1, secondStats.reused)
        assertEquals(0, secondStats.generated)
        assertEquals(0, second.fetchCalls)
    }

    @Test
    fun skipsHttpThumbnailsInsteadOfRenderingThemAsFiles() = runTest {
        val configJson = Json.encodeToString(DriveSourceConfig(accountEmail = "user@example.com"))
        val item = RemoteMedia(
            remoteId = "drive-file-1",
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            sizeBytes = 5000,
            dateTakenEpochMs = 1000L,
            thumbnailUrl = "https://example.test/thumb.jpg",
        )
        val db = newDb()
        val repo = MediaRepository(db)
        repo.upsertCloudSource("drive_1", SourceType.GOOGLE_DRIVE, "Drive", configJson)
        val config = SourceConfig(
            id = "drive_1",
            type = SourceType.GOOGLE_DRIVE,
            displayName = "Drive",
            enabled = true,
            isUploadTarget = false,
            configJson = configJson,
        )
        MediaIndexer(db).index(CountingSource(config, item)).getOrThrow()

        // Coil loads the HTTPS URL directly, so generation is a no-op here.
        assertEquals(0, ThumbnailStore(db).generateMissing().completed)
        assertEquals(0L, db.mediaItemQueries.countMissingThumbnails().executeAsOne())
    }

    private fun nasConfig(id: String, configJson: String) = SourceConfig(
        id = id,
        type = SourceType.NAS_ARCHIVE,
        displayName = "NAS",
        enabled = true,
        isUploadTarget = false,
        configJson = configJson,
    )

    /** Minimal source that writes a valid JPEG so cache reuse can be measured. */
    private class CountingSource(
        override val config: SourceConfig,
        private val item: RemoteMedia,
        var fetchCalls: Int = 0,
    ) : MediaSource {
        override suspend fun list(cursor: String?): Result<MediaPage> =
            Result.success(MediaPage(listOf(item), null))

        override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> {
            fetchCalls++
            val file = File(target.toString())
            file.parentFile?.mkdirs()
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", file)
            return Result.success(target)
        }

        override suspend fun downloadOriginal(
            item: RemoteMedia,
            target: Path,
            onProgress: (Float) -> Unit,
        ): Result<Path> = Result.failure(UnsupportedOperationException())
    }
}
