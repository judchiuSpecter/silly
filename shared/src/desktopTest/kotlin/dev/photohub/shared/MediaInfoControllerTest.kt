package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaInfoController
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.PhotoMetadata
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaInfoControllerTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    private fun seed(db: PhotoHubDatabase, sourceType: String = "LOCAL") {
        db.sourceQueries.upsert(
            id = "src", type = sourceType, displayName = "This device", enabled = 1,
            isUploadTarget = 0, configJson = "{}", lastSyncedAt = null, sortOrder = 0,
            lastSyncError = null,
        )
        db.mediaItemQueries.upsert(
            id = "src:1", sourceId = "src", remoteId = "/pictures/one.jpg",
            displayName = "one.jpg", mimeType = "image/jpeg", isVideo = 0,
            sizeBytes = 4_200_000, width = 4032, height = 3024, dateTaken = 1000,
            contentHash = null, thumbCachePath = null, originalCachePath = null,
            syncState = "INDEXED", remoteThumbUrl = null, indexedAt = 0,
        )
    }

    @Test
    fun readsExifOnceAndServesItFromTheIndexAfterwards() = runTest {
        val db = newDb()
        seed(db)
        val repository = MediaRepository(db)
        val item = repository.itemById("src:1")!!
        var reads = 0

        val controller = MediaInfoController(
            repository = repository,
            readMetadata = { _, _ ->
                reads++
                PhotoMetadata(make = "Apple", model = "iPhone 15", iso = 125, latitude = 14.5)
            },
            networkAllowed = { false },
        )

        val first = controller.info(item)
        assertEquals("Apple", first.metadata?.make)
        assertEquals(125, first.metadata?.iso)
        assertEquals(14.5, first.metadata?.latitude)
        assertEquals(1, reads)

        // A second open must not touch the file again — that is the whole point
        // of caching EXIF onto the indexed row.
        val second = controller.info(item)
        assertEquals(125, second.metadata?.iso)
        assertEquals(1, reads)
    }

    @Test
    fun aRemoteRowDoesNotDownloadOnAMeteredConnection() = runTest {
        val db = newDb()
        seed(db, sourceType = "GOOGLE_DRIVE")
        val repository = MediaRepository(db)
        val item = repository.itemById("src:1")!!
        var downloads = 0

        val controller = MediaInfoController(
            repository = repository,
            readMetadata = { _, _ -> PhotoMetadata(iso = 800) },
            ensureOriginal = { downloads++; null },
            networkAllowed = { false },
        )

        val info = controller.info(item)
        assertNull(info.metadata)
        assertEquals(0, downloads)
        assertTrue(info.note?.contains("Wi-Fi") == true)
        assertEquals(4_200_000, info.sizeBytes)
        assertEquals("This device", info.sourceName)
    }

    @Test
    fun aRemoteRowDownloadsTheOriginalWhenUnmetered() = runTest {
        val db = newDb()
        seed(db, sourceType = "GOOGLE_DRIVE")
        val repository = MediaRepository(db)
        val item = repository.itemById("src:1")!!
        var downloads = 0

        val controller = MediaInfoController(
            repository = repository,
            readMetadata = { _, _ -> PhotoMetadata(iso = 800, aperture = 1.8) },
            ensureOriginal = { downloads++; "/cache/original.bin" },
            networkAllowed = { true },
        )

        val info = controller.info(item)
        assertEquals(800, info.metadata?.iso)
        assertEquals(1.8, info.metadata?.aperture)
        assertEquals(1, downloads)
    }
}
