package dev.photohub.shared.platform

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.GpsDirectory
import dev.photohub.shared.model.PhotoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Desktop EXIF reader, backed by metadata-extractor (already used by the HEIC decoder). */
actual suspend fun readPhotoMetadata(ref: String, mimeType: String): PhotoMetadata? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = File(ref)
            if (!file.isFile) return@withContext null
            val metadata = ImageMetadataReader.readMetadata(file)
            val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)

            fun text(directory: Directory?, tag: Int): String? =
                directory?.getString(tag)?.trim()?.takeIf { it.isNotBlank() }

            fun number(directory: Directory?, tag: Int): Double? =
                directory?.getDouble(tag)?.takeIf { !it.isNaN() }

            val location = runCatching { gps?.geoLocation }.getOrNull()

            PhotoMetadata(
                make = text(ifd0, ExifDirectoryBase.TAG_MAKE),
                model = text(ifd0, ExifDirectoryBase.TAG_MODEL),
                lens = text(ifd0, ExifDirectoryBase.TAG_LENS_MODEL),
                iso = number(ifd0, ExifDirectoryBase.TAG_ISO_EQUIVALENT)?.toInt(),
                aperture = number(ifd0, ExifDirectoryBase.TAG_FNUMBER),
                shutter = text(ifd0, ExifDirectoryBase.TAG_EXPOSURE_TIME),
                focalLength = number(ifd0, ExifDirectoryBase.TAG_FOCAL_LENGTH),
                latitude = location?.latitude,
                longitude = location?.longitude,
                originalDate = text(ifd0, ExifDirectoryBase.TAG_DATETIME),
                software = text(ifd0, ExifDirectoryBase.TAG_SOFTWARE),
                orientation = number(ifd0, ExifDirectoryBase.TAG_ORIENTATION)?.toInt(),
            )
        }.getOrNull()
    }
