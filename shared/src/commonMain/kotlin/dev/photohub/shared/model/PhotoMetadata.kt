package dev.photohub.shared.model

import kotlinx.serialization.Serializable

/**
 * The small set of EXIF/XMP fields worth surfacing in the photo-info panel.
 *
 * Stored as JSON on the indexed row so opening the panel later does not have to
 * re-read an original (which, for cloud items, could mean another download).
 * Everything is nullable: plenty of sources strip metadata, and a missing tag
 * should simply leave that row out of the panel rather than showing a fake 0.
 */
@Serializable
data class PhotoMetadata(
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val iso: Int? = null,
    val aperture: Double? = null,
    val shutter: String? = null,
    val focalLength: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val originalDate: String? = null,
    val software: String? = null,
    val orientation: Int? = null,
)
