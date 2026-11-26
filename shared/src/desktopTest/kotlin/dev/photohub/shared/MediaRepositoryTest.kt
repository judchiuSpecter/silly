package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.db.upsert
import dev.photohub.shared.model.SourceType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    @Test
    fun emptyTimelineIsEmpty() {
        val repo = MediaRepository(newDb())
        assertEquals(0, repo.timelinePage(limit = 50, offset = 0).size)
        assertEquals(0L, repo.totalCount())
    }

    @Test
    fun insertedItemAppearsInTimelineNewestFirst() {
        val db = newDb()
        db.sourceQueries.upsert(
            id = "local",
            type = "LOCAL",
            displayName = "This device",
            enabled = 1,
            isUploadTarget = 0,
            configJson = "{}",
            lastSyncedAt = null,
            sortOrder = 0,
            lastSyncError = null,
        )
        // Two items; the newer dateTaken must come first.
        db.mediaItemQueries.upsert(
            id = "local:1", sourceId = "local", remoteId = "1",
            displayName = "older.jpg", mimeType = "image/jpeg", isVideo = 0,
            sizeBytes = 100, width = null, height = null, dateTaken = 1000,
            contentHash = null, thumbCachePath = null, originalCachePath = null,
            syncState = "INDEXED", remoteThumbUrl = null, indexedAt = 0,
        )
        db.mediaItemQueries.upsert(
            id = "local:2", sourceId = "local", remoteId = "2",
            displayName = "newer.jpg", mimeType = "image/jpeg", isVideo = 0,
            sizeBytes = 100, width = null, height = null, dateTaken = 2000,
            contentHash = null, thumbCachePath = null, originalCachePath = null,
            syncState = "INDEXED", remoteThumbUrl = null, indexedAt = 0,
        )

        val repo = MediaRepository(db)
        val page = repo.timelinePage(limit = 50, offset = 0)
        assertEquals(2, page.size)
        assertEquals("newer.jpg", page.first().displayName)
        assertNotNull(repo.itemById("local:1"))
    }

    @Test
    fun onlyOneMoveTargetAllowed() {
        val repo = MediaRepository(newDb())
        repo.upsertCloudSource("drive_a", SourceType.GOOGLE_DRIVE, "Drive A")
        repo.upsertCloudSource("drive_b", SourceType.GOOGLE_DRIVE, "Drive B")

        repo.setMoveTarget("drive_a")
        repo.setMoveTarget("drive_b")

        assertEquals("drive_b", repo.moveTarget()?.id)
        assertEquals(
            1,
            repo.allSources().count { it.isMoveTarget },
            "moving the slot must clear the previous destination",
        )
    }

    @Test
    fun startupDoesNotAssignADefaultMoveTarget() {
        val repo = MediaRepository(newDb())
        repo.upsertCloudSource(
            id = "nas_legacy",
            type = SourceType.NAS_ARCHIVE,
            displayName = "NAS",
        )

        repo.ensureLocalSource()

        assertNull(repo.moveTarget(), "Move destinations are chosen per operation")
    }

    @Test
    fun sourceVisibilityIsPersisted() {
        val repo = MediaRepository(newDb())
        repo.upsertCloudSource("drive_a", SourceType.GOOGLE_DRIVE, "Drive A")

        repo.setSourceVisible("drive_a", false)

        assertEquals(false, repo.allSources().single { it.id == "drive_a" }.showInTimeline)
    }

    @Test
    fun onlyOneUploadTargetAllowed() {
        val db = newDb()
        db.sourceQueries.upsert(
            id = "drive", type = "GOOGLE_DRIVE", displayName = "Drive",
            enabled = 1, isUploadTarget = 1, configJson = "{}",
            lastSyncedAt = null, sortOrder = 0, lastSyncError = null,
        )
        db.sourceQueries.clearAllUploadTargets()
        db.sourceQueries.upsert(
            id = "mega", type = "MEGA", displayName = "MEGA",
            enabled = 1, isUploadTarget = 1, configJson = "{}",
            lastSyncedAt = null, sortOrder = 1, lastSyncError = null,
        )
        val target = db.sourceQueries.selectUploadTarget().executeAsOneOrNull()
        assertEquals("mega", target?.id)
    }

    @Test
    fun deletingSourceRemovesOnlyUnreferencedCacheFiles() {
        val dir = Files.createTempDirectory("source-cleanup").toFile()
        val sharedThumb = File(dir, "shared-thumb.jpg").apply { writeText("thumb") }
        val onlyOriginal = File(dir, "original.bin").apply { writeText("original") }
        val db = newDb()
        val repo = MediaRepository(db)

        listOf("nas_1", "nas_2").forEach { id ->
            repo.upsertCloudSource(id, SourceType.NAS_ARCHIVE, "NAS")
            db.mediaItemQueries.upsert(
                id = "$id:photo", sourceId = id, remoteId = "photo",
                displayName = "photo.jpg", mimeType = "image/jpeg", isVideo = 0,
                sizeBytes = 100, width = null, height = null, dateTaken = 1000,
                contentHash = null, thumbCacheKey = "nas|host|photo|100",
                thumbCachePath = sharedThumb.absolutePath,
                originalCachePath = if (id == "nas_1") onlyOriginal.absolutePath else null,
                syncState = "THUMB_CACHED", remoteThumbUrl = null, indexedAt = 0,
            )
        }

        repo.deleteSource("nas_1")

        assertNull(repo.itemById("nas_1:photo"))
        assertNotNull(repo.itemById("nas_2:photo"))
        assertTrue(sharedThumb.isFile, "a thumbnail still used by nas_2 must survive")
        assertFalse(onlyOriginal.isFile, "an unreferenced original must be removed")
    }
}
