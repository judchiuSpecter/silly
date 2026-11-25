package dev.photohub.shared

import dev.photohub.shared.source.drive.parseWhatsAppFileName
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriveDatesTest {

    @Test
    fun parsesImgPrefixedWhatsAppFileName() {
        val epochMs = parseWhatsAppFileName("IMG-20230923-WA0003.jpg", TimeZone.UTC)
        assertEquals("2023-09-23T00:00:00Z", kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs!!).toString())
    }

    @Test
    fun parsesVidPrefixedWhatsAppFileName() {
        val epochMs = parseWhatsAppFileName("VID-20220417-WA0000.mp4", TimeZone.UTC)
        assertEquals("2022-04-17T00:00:00Z", kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs!!).toString())
    }

    @Test
    fun ignoresNonWhatsAppFileNames() {
        assertNull(parseWhatsAppFileName("20260812_194605.jpg"))
        assertNull(parseWhatsAppFileName("vacation.jpg"))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertNull(parseWhatsAppFileName("IMG-20231399-WA0000.jpg"))
    }
}
