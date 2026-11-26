package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the timeline: exposes the cached items the UI renders and refreshes
 * them by re-indexing every enabled source.
 *
 * The cached rows are published first and the index runs afterwards, so the
 * timeline appears instantly offline and then fills in — the app never blocks
 * on a scan. Local sources go first because they are instant and offline; a
 * cloud failure must not discard items already indexed from elsewhere.
 *
 * A source can fail by *throwing* rather than returning [Result.failure] —
 * MEGA's client throws when the account is blocked, for instance — so every
 * source is attempted inside its own `runCatching`. One broken account must
 * never crash the refresh loop or take the rest of the timeline down with it.
 * A source with a recorded error is also skipped on the next automatic
 * refresh entirely (not just after a thrown exception), so a dead account
 * cannot be hammered by the periodic background sync; the Sources screen
 * surfaces the error with a manual retry that clears it and tries again.
 */
class TimelineController(
    private val repository: MediaRepository,
    private val indexer: MediaIndexer,
    private val thumbnails: ThumbnailStore,
    private val sources: SourceRegistry,
) {
    /** What went wrong on the last refresh, or null when it succeeded. */
    data class State(
        val items: List<MediaItem> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Refresh lives outside Compose's scope. A 96k-source walk takes minutes;
     * tying it to a `LaunchedEffect` meant any recomposition, rotation, or
     * screen transition cancelled it mid-login and surfaced a spurious error.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    /** Starts a refresh without blocking the caller; ignores duplicate taps. */
    fun requestRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = backgroundScope.launch { refresh() }
    }

    /** Publishes whatever is already cached, without touching any source. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(items = page(), error = null)
    }

    /**
     * Links one freshly fetched thumbnail into the published timeline. Republishing
     * the whole page for every visible cell would re-read and dedupe ~100k rows
     * per thumbnail, so grid cells update just their own item instead.
     */
    fun updateThumbnail(itemId: String, path: String) {
        _state.value = _state.value.copy(
            items = _state.value.items.map { item ->
                if (item.id == itemId) {
                    item.copy(thumbCachePath = path, syncState = "THUMB_CACHED")
                } else {
                    item
                }
            },
        )
    }

    /**
     * Re-indexes all enabled sources and republishes the timeline.
     *
     * Safe to call when permission has not been granted: the local source
     * returns a failure (or an empty list) and the cached timeline is left in
     * place.
     *
     * A source already flagged with [dev.photohub.shared.model.SourceConfig.lastSyncError]
     * is skipped here — it had a real failure on a previous pass, and retrying
     * it automatically (especially from a periodic background sync) risks
     * hammering a broken or blocked account. [retrySource] re-attempts one by
     * hand, which is how the alert on the Sources screen gets cleared.
     */
    suspend fun refresh() {
        _state.value = _state.value.copy(isRefreshing = true, error = null)

        // Anything below can throw (a broken media row, an unsupported codec
        // during thumbnail generation), and a suspended refresh coroutine that
        // escapes would leave the top-bar spinner up forever. Always clear it.
        try {
            // Indexing 100k NAS rows writes a huge SQLite transaction. Keeping
            // that on the UI dispatcher makes the phone ANR mid-refresh.
            withContext(Dispatchers.IO) {
            // The device source is implicit: make sure its row exists before the
            // first scan, but never reset its settings on later launches.
            val localId = repository.ensureLocalSource().id
            val configs = repository.allSources()
                .sortedWith(compareBy({ if (it.type == SourceType.LOCAL) 0 else 1 }, { it.id }))

            var localError: String? = null
            val cloudErrors = mutableListOf<String>()

            for (config in configs) {
                val isLocal = config.id == localId
                // The local source is skipped from the persisted-error gate: its
                // failures are almost always a revoked permission, which can be
                // re-granted at any moment, so it must keep trying on every
                // refresh rather than needing a manual retry like a cloud account.
                if (!isLocal && config.lastSyncError != null) continue
                val failure = syncOne(config, persistError = !isLocal)
                if (isLocal) {
                    localError = failure
                } else if (failure != null) {
                    cloudErrors += "${config.displayName}: $failure"
                }

                // Show newly indexed items after every source, not just at the end:
                // one unreachable cloud must not hold up the rest of the grid.
                _state.value = _state.value.copy(items = page())
            }

            thumbnails.generateMissing(
                onProgress = { completed ->
                    // Republish periodically so the grid sharpens as it goes rather
                    // than staying blank until the whole library is done.
                    if (completed % THUMB_REFRESH_INTERVAL == 0) {
                        _state.value = _state.value.copy(items = page())
                    }
                },
            )

            _state.value = _state.value.copy(
                items = page(),
                // Keep a local failure if there was one; otherwise report the clouds.
                error = localError ?: cloudErrors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
            }
        } finally {
            _state.value = _state.value.copy(isRefreshing = false)
        }
    }

    /** Republishes the cached page after an on-demand thumbnail fetch. */
    fun republish() {
        _state.value = _state.value.copy(items = page())
    }

    /**
     * Re-attempts exactly one source, bypassing the "already failed" skip in
     * [refresh]. This is what the Sources screen's retry action calls: it
     * clears the alert on success, or replaces it with whatever went wrong
     * this time.
     */
    suspend fun retrySource(sourceId: String) = withContext(Dispatchers.IO) {
        val config = repository.sourceById(sourceId) ?: return@withContext
        syncOne(config, persistError = true)
        _state.value = _state.value.copy(items = page())
    }

    /**
     * Builds and indexes one source, optionally recording the outcome on its
     * row so the Sources screen can show an alert and future automatic
     * refreshes can skip it.
     *
     * Everything here — building the client graph, listing, and (for sources
     * with no server-side thumbnail URL) rendering previews — can throw rather
     * than return a [Result], so it all runs inside one `runCatching`. Returns
     * a human-readable failure message, or null on success.
     */
    private suspend fun syncOne(config: SourceConfig, persistError: Boolean): String? {
        val outcome = runCatching {
            val source = sources.create(config) ?: return@runCatching
            indexer.index(source).getOrThrow()
            if (config.type in REMOTE_THUMB_TYPES) {
                // NAS has no server-side thumbnails here: each miss falls back to
                // a full original download. Keep each pass tiny so one slow file
                // cannot stall the whole refresh or trip the phone's patience.
                val limit = if (config.type == SourceType.NAS_ARCHIVE) {
                    NAS_THUMB_BATCH
                } else {
                    ThumbnailStore.REMOTE_THUMB_LIMIT
                }
                thumbnails.fetchRemote(source, limit = limit, onProgress = { completed ->
                    if (completed % THUMB_REFRESH_INTERVAL == 0) {
                        _state.value = _state.value.copy(items = page())
                    }
                })
            }
        }
        val failure = outcome.exceptionOrNull()
        val message = failure?.let { it.message?.takeIf { msg -> msg.isNotBlank() } ?: "sync failed" }
        if (failure != null) {
            // Keep the card short but leave the full cause chain in logcat: an
            // SFTP abort can mean DNS, handshake, a killed remote command or a
            // dropped socket, and those need different fixes.
            val detail = buildString {
                append("[sync:${config.id}] ")
                var cause: Throwable? = failure
                while (cause != null) {
                    if (this.isNotBlank() && !endsWith("] ")) append(" <- ")
                    append(cause::class.simpleName).append(": ").append(cause.message)
                    cause = cause.cause
                }
            }
            println(detail)
        }
        if (persistError) repository.setSyncError(config.id, message)
        return message
    }

    private fun page(): List<MediaItem> =
        repository.timelinePage(limit = PAGE_LIMIT, offset = 0)

    private companion object {
        // One screenful of a large library; paging in on scroll comes with M5.
        // The date rail needs the whole library to have something to seek to;
        // a 2,000-row page can be entirely one import day and collapses to a
        // couple of sections after dedupe. LazyVerticalGrid handles 100k rows.
        const val PAGE_LIMIT = 100_000L

        // How many thumbnails to finish before republishing the timeline.
        const val THUMB_REFRESH_INTERVAL = 4

        // NAS misses download the original, so only a handful per refresh.
        const val NAS_THUMB_BATCH = 6

        // Sources with no server-side thumbnail URL, so ThumbnailStore has to
        // download and render one locally instead of just linking to it.
        val REMOTE_THUMB_TYPES = setOf(SourceType.MEGA, SourceType.NAS_ARCHIVE)
    }
}
