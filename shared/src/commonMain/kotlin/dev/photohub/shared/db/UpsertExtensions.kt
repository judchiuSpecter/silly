package dev.photohub.shared.db

import app.cash.sqldelight.db.QueryResult

/**
 * Insert-or-update as two statements rather than SQLite's
 * `ON CONFLICT ... DO UPDATE`: some Android devices ship an SQLite build old
 * enough (pre-3.24) to reject upsert syntax outright. Keeping the same name
 * and parameter order as the old single-statement upsert means call sites
 * only need an import, not a rewrite.
 */
fun SourceQueries.upsert(
    id: String,
    type: String,
    displayName: String,
    enabled: Long,
    isUploadTarget: Long,
    configJson: String,
    lastSyncedAt: Long?,
    sortOrder: Long,
    lastSyncError: String?,
): QueryResult<Long> {
    insertIgnoringExisting(
        id = id,
        type = type,
        displayName = displayName,
        enabled = enabled,
        isUploadTarget = isUploadTarget,
        configJson = configJson,
        lastSyncedAt = lastSyncedAt,
        sortOrder = sortOrder,
        lastSyncError = lastSyncError,
    )
    return updateExisting(
        type = type,
        displayName = displayName,
        enabled = enabled,
        isUploadTarget = isUploadTarget,
        configJson = configJson,
        lastSyncedAt = lastSyncedAt,
        sortOrder = sortOrder,
        lastSyncError = lastSyncError,
        id = id,
    )
}

fun MediaItemQueries.upsert(
    id: String,
    sourceId: String,
    remoteId: String,
    displayName: String,
    mimeType: String,
    isVideo: Long,
    sizeBytes: Long,
    width: Long?,
    height: Long?,
    dateTaken: Long,
    contentHash: String?,
    thumbCachePath: String?,
    originalCachePath: String?,
    syncState: String,
    remoteThumbUrl: String?,
    indexedAt: Long,
): QueryResult<Long> {
    insertIgnoringExisting(
        id = id,
        sourceId = sourceId,
        remoteId = remoteId,
        displayName = displayName,
        mimeType = mimeType,
        isVideo = isVideo,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        dateTaken = dateTaken,
        contentHash = contentHash,
        thumbCachePath = thumbCachePath,
        originalCachePath = originalCachePath,
        syncState = syncState,
        remoteThumbUrl = remoteThumbUrl,
        indexedAt = indexedAt,
    )
    return updateExisting(
        displayName = displayName,
        mimeType = mimeType,
        isVideo = isVideo,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        dateTaken = dateTaken,
        contentHash = contentHash,
        remoteThumbUrl = remoteThumbUrl,
        indexedAt = indexedAt,
        id = id,
    )
}
