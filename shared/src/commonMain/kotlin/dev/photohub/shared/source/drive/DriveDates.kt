package dev.photohub.shared.source.drive

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Parses the two different timestamp formats Drive returns.
 *
 * `createdTime`/`modifiedTime` are RFC 3339 UTC (`2026-11-21T14:03:22.000Z`),
 * but `imageMediaMetadata.time` is the raw EXIF string
 * (`2026:11:21 14:03:22`) — colon-separated, with no zone at all. Feeding the
 * EXIF form to an RFC 3339 parser throws, which would silently drop the only
 * accurate capture time we get, so both shapes are handled here.
 *
 * Returns null for absent or unparseable input; callers fall back to another field.
 */
internal fun parseRfc3339(value: String?): Long? {
    if (value.isNullOrBlank()) return null

    // RFC 3339 / ISO 8601, what Drive uses for file timestamps.
    runCatching { return Instant.parse(value).toEpochMilliseconds() }

    return parseExifDateTime(value)
}

/**
 * Parses an EXIF `YYYY:MM:DD HH:MM:SS` timestamp.
 *
 * EXIF carries no time zone, and the convention is that it records the camera's
 * local wall-clock time. It is therefore interpreted in the device's current
 * zone, which keeps a photo on the day the user remembers taking it — the same
 * choice the timeline's day grouping makes.
 */
internal fun parseExifDateTime(
    value: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Long? {
    val trimmed = value.trim()
    // "2026:11:21 14:03:22" — 19 chars; anything shorter is not this format.
    if (trimmed.length < 19) return null

    val date = trimmed.substring(0, 10).split(':', '-')
    val time = trimmed.substring(11, 19).split(':')
    if (date.size != 3 || time.size != 3) return null

    val year = date[0].toIntOrNull() ?: return null
    val month = date[1].toIntOrNull() ?: return null
    val day = date[2].toIntOrNull() ?: return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null

    // Some cameras write all zeros when the clock was never set; that is not a
    // real date and must not become 0000-01-01 in the timeline.
    if (year < 1900 || month !in 1..12 || day !in 1..31) return null

    return runCatching {
        LocalDateTime(year, month, day, hour, minute, second)
            .toInstant(zone)
            .toEpochMilliseconds()
    }.getOrNull()
}

/** `IMG-YYYYMMDD-WA####.*`, the filename WhatsApp gives a saved photo or video. */
private val whatsAppFileNamePattern = Regex("""^(?:IMG|VID)-(\d{4})(\d{2})(\d{2})-WA\d+""")

/**
 * Recovers the capture date from a WhatsApp-style filename.
 *
 * WhatsApp strips EXIF (and `imageMediaMetadata.time`/`videoMediaMetadata`
 * along with it) from everything it saves, so Drive's only other timestamps
 * are `createdTime`/`modifiedTime` — when the file was uploaded, not taken.
 * For a library migrated or backed up well after the fact, that is the day
 * of the upload for every such file, which pushes years of old WhatsApp
 * photos onto one day in the timeline, each looking like a "new" duplicate of
 * whatever was actually taken that day. WhatsApp encodes the real date in the
 * filename itself, so that is tried before the upload-time fallbacks.
 *
 * No time-of-day is in the filename, so this returns local midnight — later
 * than EXIF would give, but it keeps the item on the right day, which is what
 * the timeline groups by.
 */
internal fun parseWhatsAppFileName(
    name: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Long? {
    val match = whatsAppFileNamePattern.find(name) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toIntOrNull() ?: return null
    val month = monthText.toIntOrNull() ?: return null
    val day = dayText.toIntOrNull() ?: return null
    if (year < 1900 || month !in 1..12 || day !in 1..31) return null

    return runCatching {
        LocalDateTime(year, month, day, 0, 0, 0)
            .toInstant(zone)
            .toEpochMilliseconds()
    }.getOrNull()
}
