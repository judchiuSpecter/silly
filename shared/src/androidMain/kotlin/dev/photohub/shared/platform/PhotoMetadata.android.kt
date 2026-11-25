package dev.photohub.shared.platform

import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.photohub.shared.model.PhotoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android EXIF reader for the info panel.
 *
 * `content://` items are opened through a file descriptor rather than copied to
 * a temp file: MediaStore exposes the original directly, and a modern phone
 * photo can be tens of megabytes. The ExifInterface constructor reads eagerly,
 * so closing the descriptor afterwards is safe.
 */
actual suspend fun readPhotoMetadata(ref: String, mimeType: String): PhotoMetadata? =
    withContext(Dispatchers.IO) {
        val exif = openExif(ref) ?: return@withContext null
        runCatching {
            fun text(tag: String): String? = exif.getAttribute(tag)?.trim()?.takeIf { it.isNotBlank() }
            fun number(tag: String): Double? = text(tag)?.toDoubleOrNull()
            val latLong = runCatching { exif.latLong }.getOrNull()

            PhotoMetadata(
                make = text("Make"),
                model = text("Model"),
                lens = text("LensModel"),
                iso = text("ISOSpeedRatings")?.toIntOrNull() ?: text("ISO")?.toIntOrNull(),
                aperture = number("FNumber"),
                shutter = text("ExposureTime"),
                focalLength = number("FocalLength"),
                latitude = latLong?.getOrNull(0),
                longitude = latLong?.getOrNull(1),
                originalDate = text("DateTime"),
                software = text("Software"),
                orientation = text("Orientation")?.toIntOrNull(),
            )
        }.getOrNull()
    }

private fun openExif(ref: String): ExifInterface? = runCatching {
    when {
        ref.startsWith("content://") -> {
            val descriptor = AndroidPlatform.appContext.contentResolver
                .openFileDescriptor(Uri.parse(ref), "r") ?: return@runCatching null
            descriptor.use { ExifInterface(it.fileDescriptor) }
        }
        ref.startsWith("file://") -> {
            val path = Uri.parse(ref).path ?: return@runCatching null
            ExifInterface(path)
        }
        else -> ExifInterface(ref)
    }
}.getOrNull()
