package dev.photohub.shared

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.ui.BulkDateMode
import dev.photohub.shared.ui.BulkSizeMode
import dev.photohub.shared.ui.BulkTypeFilter
import dev.photohub.shared.ui.matchesBulkQuery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkSelectTest {

    private val utc = TimeZone.UTC

    private fun item(
        id: String,
        name: String = "$id.jpg",
        date: String = "2026-01-10",
        sizeMb: Double = 2.0,
        sourceId: String = "drive_a",
        isVideo: Long = 0,
        syncState: String = "INDEXED",
    ): MediaItem = MediaItem(
        id = id,
        sourceId = sourceId,
        remoteId = id,
        displayName = name,
        mimeType = if (isVideo == 1L) "video/mp4" else "image/jpeg",
        isVideo = isVideo,
        sizeBytes = (sizeMb * 1_000_000L).toLong(),
        width = null,
        height = null,
        dateTaken = LocalDate.parse(date).atStartOfDayIn(utc).toEpochMilliseconds(),
        contentHash = null,
        metadataJson = null,
        thumbCachePath = null,
        originalCachePath = null,
        syncState = syncState,
        remoteThumbUrl = null,
        indexedAt = 0,
    )

    @Test
    fun eachRuleMatchesAndCombines() {
        val early = item("a", name = "IMG_001.jpg", date = "2026-01-02", sizeMb = 1.0)
        val late = item("b", name = "IMG_002.jpg", date = "2026-01-20", sizeMb = 5.0)
        val video = item(
            "c",
            name = "VID_002.jpg",
            date = "2026-01-20",
            sizeMb = 5.0,
            isVideo = 1,
            syncState = "ORIGINAL_CACHED",
        )

        assertTrue(
            matchesBulkQuery(
                item = early,
                date = LocalDate.parse("2026-01-10"),
                dateMode = BulkDateMode.BEFORE,
                zone = utc,
            ),
        )
        assertFalse(
            matchesBulkQuery(
                item = late,
                date = LocalDate.parse("2026-01-10"),
                dateMode = BulkDateMode.BEFORE,
                zone = utc,
            ),
        )
        assertTrue(
            matchesBulkQuery(
                item = video,
                date = LocalDate.parse("2026-01-10"),
                dateMode = BulkDateMode.AFTER,
                zone = utc,
            ),
        )

        assertTrue(matchesBulkQuery(late, sizeMb = 4.0, sizeMode = BulkSizeMode.LARGER))
        assertFalse(matchesBulkQuery(early, sizeMb = 4.0, sizeMode = BulkSizeMode.LARGER))
        assertTrue(matchesBulkQuery(early, sizeMb = 4.0, sizeMode = BulkSizeMode.SMALLER))

        assertTrue(matchesBulkQuery(late, sourceId = "drive_a"))
        assertFalse(matchesBulkQuery(late, sourceId = "nas"))

        assertTrue(matchesBulkQuery(late, nameContains = "img_002"))
        assertFalse(matchesBulkQuery(late, nameContains = "IMG_999"))

        assertTrue(matchesBulkQuery(video, type = BulkTypeFilter.VIDEO))
        assertFalse(matchesBulkQuery(video, type = BulkTypeFilter.PHOTO))
        assertTrue(matchesBulkQuery(early, type = BulkTypeFilter.PHOTO))

        assertTrue(matchesBulkQuery(video, syncState = "ORIGINAL_CACHED"))
        assertFalse(matchesBulkQuery(early, syncState = "ORIGINAL_CACHED"))

        assertTrue(
            matchesBulkQuery(
                item = video,
                date = LocalDate.parse("2026-01-10"),
                dateMode = BulkDateMode.AFTER,
                sizeMb = 4.0,
                sizeMode = BulkSizeMode.LARGER,
                sourceId = "drive_a",
                nameContains = "vid",
                type = BulkTypeFilter.VIDEO,
                syncState = "ORIGINAL_CACHED",
                zone = utc,
            ),
        )
        assertFalse(
            matchesBulkQuery(
                item = early,
                date = LocalDate.parse("2026-01-10"),
                dateMode = BulkDateMode.AFTER,
                sizeMb = 4.0,
                sizeMode = BulkSizeMode.LARGER,
                sourceId = "drive_a",
                nameContains = "vid",
                type = BulkTypeFilter.VIDEO,
                syncState = "ORIGINAL_CACHED",
                zone = utc,
            ),
        )
    }
}
