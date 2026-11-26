package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.data.MediaInfo
import dev.photohub.shared.data.MediaInfoController
import dev.photohub.shared.data.PhotoActionsController
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.db.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The two screens PhotoHub has. Kept as an enum — a nav library would be overkill. */
private enum class Screen { TIMELINE, ACCOUNTS }

/**
 * Root composable shared by Android and desktop.
 *
 * Renders whatever the [TimelineController] has cached and triggers a refresh
 * on first composition. [refreshOnStart] is false on Android until the media
 * permission has been granted, so the scan is not wasted.
 *
 * [accounts] is nullable so a host can still show the timeline if the accounts
 * graph is unavailable; the settings action is simply hidden in that case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    controller: TimelineController,
    accounts: AccountsController? = null,
    refreshOnStart: Boolean = true,
    settings: SettingsStore = SettingsStore(),
    fullscreenImages: FullscreenImageController? = null,
    photoActions: PhotoActionsController? = null,
    mediaInfo: MediaInfoController? = null,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.TIMELINE) }
    val prefs by settings.settings.collectAsState()

    val noActions = remember { MutableStateFlow(PhotoActionsController.State()) }
    val actionState by (photoActions?.state ?: noActions).collectAsState()

    val noAccounts = remember { MutableStateFlow(AccountsController.State()) }
    val accountState by (accounts?.state ?: noAccounts).collectAsState()
    // A null accounts graph cannot express visibility, so it shows everything.
    val visibleIds = remember(accounts, accountState.sources) {
        if (accounts == null) {
            null
        } else {
            accountState.sources.filter { it.showInTimeline }.map { it.id }.toSet()
        }
    }
    val visibleItems = remember(state.items, visibleIds) {
        visibleIds?.let { ids -> state.items.filter { it.sourceId in ids } } ?: state.items
    }
    val sourceNames = remember(accountState.sources) {
        accountState.sources.associate { it.id to it.displayName }
    }
    val hasAnyMoveDestination = accountState.sources.any {
        it.canReceiveMoves && it.state == AccountsController.LinkState.LINKED
    }

    /** Item being shown full-screen, or null when on the timeline grid. */
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }
    /** Ids selected on the timeline; empty means normal single-item navigation. */
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Search prompt opened from the magnifier in the top bar. */
    var showSearch by remember { mutableStateOf(false) }
    /** Non-null while the flat, multiselect results grid replaces the timeline. */
    var searchResults by remember { mutableStateOf<List<MediaItem>?>(null) }

    fun exitSearchResults() {
        searchResults = null
        selectedIds = emptySet()
    }

    /** Non-null while the delete confirmation dialog is on screen. */
    var pendingDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    /** Items waiting for the user to pick a Move destination. */
    var pendingMove by remember { mutableStateOf<List<MediaItem>?>(null) }
    var showNoMoveDestination by remember { mutableStateOf(false) }
    /** Item whose info panel is open, from either the grid or the viewer. */
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    var infoPanelState by remember { mutableStateOf(MediaInfoPanelState()) }

    LaunchedEffect(infoItem?.id) {
        val item = infoItem ?: return@LaunchedEffect
        infoPanelState = MediaInfoPanelState(loading = true)
        // The App's state may have refreshed since the tap; prefer the fresh row
        // so a newly cached original or EXIF blob is visible immediately.
        val fresh = visibleItems.firstOrNull { it.id == item.id } ?: item
        val result = runCatching {
            mediaInfo?.info(fresh) ?: MediaInfo.from(fresh, fresh.sourceId)
        }
        infoPanelState = MediaInfoPanelState(
            info = result.getOrNull(),
            error = result.exceptionOrNull()?.message,
        )
    }

    LaunchedEffect(refreshOnStart) {
        controller.loadCached()
        // Re-arm camera upload if an account is already linked. WorkManager
        // schedules survive restarts but not a reinstall, and a user who linked
        // in an earlier build never got one.
        accounts?.syncBackgroundWork()
        if (refreshOnStart) controller.refresh()
    }

    fun finishAction() {
        scope.launch {
            controller.refresh()
            selectedIds = emptySet()
            searchResults = null
            selectedItem = null
            infoItem = null
        }
    }

    fun performDelete(items: List<MediaItem>) {
        val actions = photoActions ?: return
        if (items.isEmpty()) return
        scope.launch {
            actions.delete(items)
            finishAction()
        }
    }

    fun requestDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (prefs.confirmDelete) pendingDelete = items else performDelete(items)
    }

    fun moveDestinationsFor(items: List<MediaItem>): List<MoveDestination> {
        val excluded = items.map { it.sourceId }.toSet()
        return accountState.sources
            .filter {
                it.canReceiveMoves &&
                    it.state == AccountsController.LinkState.LINKED &&
                    it.id !in excluded
            }
            .map { MoveDestination(it.id, it.displayName) }
    }

    fun requestMove(items: List<MediaItem>) {
        if (photoActions == null || items.isEmpty()) return
        if (moveDestinationsFor(items).isEmpty()) {
            showNoMoveDestination = true
            return
        }
        pendingMove = items
    }

    fun performMove(items: List<MediaItem>, destinationId: String) {
        val actions = photoActions ?: return
        if (items.isEmpty()) return
        scope.launch {
            actions.move(items, destinationId)
            finishAction()
        }
    }

    PhotoHubTheme(mode = prefs.themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val results = searchResults
                        Text(
                            when {
                                screen != Screen.TIMELINE -> "Settings"
                                results != null -> "Search results (${results.size})"
                                else -> "Silly"
                            },
                        )
                    },
                    navigationIcon = {
                        if (screen != Screen.TIMELINE) {
                            IconButton(onClick = { screen = Screen.TIMELINE }) {
                                Text("\u2190")
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.TIMELINE) {
                            if (searchResults != null) {
                                // Back out of the flat results grid into the
                                // normal, date-stamped timeline.
                                TextButton(onClick = { exitSearchResults() }) {
                                    Text("Cancel")
                                }
                            } else {
                                IconButton(onClick = { showSearch = true }) {
                                    Text("\uD83D\uDD0D")
                                }
                                if (state.isRefreshing || actionState.running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(end = 16.dp).size(24.dp),
                                    )
                                } else {
                                    IconButton(onClick = { scope.launch { controller.refresh() } }) {
                                        Text("\u21BB")
                                    }
                                }
                            }
                            if (accounts != null) {
                                IconButton(onClick = { screen = Screen.ACCOUNTS }) {
                                    Text("\u2699")
                                }
                            }
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                Column(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.TIMELINE -> {
                            state.error?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                )
                            }
                            actionState.message?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                )
                            }
                            val results = searchResults
                            if (results != null) {
                                TimelineScreen(
                                    items = results,
                                    modifier = Modifier.fillMaxSize(),
                                    selectedIds = selectedIds,
                                    onSelectionChange = { selectedIds = it },
                                    onItemClick = { selectedItem = it },
                                    onDeleteSelected = { requestDelete(it) },
                                    onMoveSelected = { requestMove(it) },
                                    sourceNames = sourceNames,
                                    canMove = hasAnyMoveDestination,
                                    busy = actionState.running,
                                    showDateHeaders = false,
                                    forceSelectionMode = true,
                                    onCancel = { exitSearchResults() },
                                    showBulkSelectButton = false,
                                )
                            } else {
                                TimelineScreen(
                                    items = visibleItems,
                                    modifier = Modifier.fillMaxSize(),
                                    selectedIds = selectedIds,
                                    onSelectionChange = { selectedIds = it },
                                    onItemClick = { selectedItem = it },
                                    onDeleteSelected = { requestDelete(it) },
                                    onMoveSelected = { requestMove(it) },
                                    sourceNames = sourceNames,
                                    canMove = hasAnyMoveDestination,
                                    busy = actionState.running,
                                )
                            }
                        }

                        Screen.ACCOUNTS -> accounts?.let {
                            AccountsScreen(
                                controller = it,
                                themeMode = prefs.themeMode,
                                onThemeModeChange = settings::setThemeMode,
                                confirmDelete = prefs.confirmDelete,
                                onConfirmDeleteChange = settings::setConfirmDelete,
                                onRetrySource = { id -> scope.launch { controller.retrySource(id) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                if (showSearch && screen == Screen.TIMELINE) {
                    BulkSelectDialog(
                        items = visibleItems,
                        sourceNames = sourceNames,
                        onDismiss = { showSearch = false },
                        onApply = { ids ->
                            showSearch = false
                            // The matched rows become the initial selection in
                            // the flat results grid, ready to refine or act on.
                            searchResults = visibleItems.filter { it.id in ids }
                            selectedIds = ids
                        },
                    )
                }

                // Fullscreen photo viewer overlay
                selectedItem?.let { current ->
                    val index = visibleItems.indexOfFirst { it.id == current.id }
                    FullscreenPhotoViewer(
                        item = current,
                        onDismiss = {
                            if (infoItem != null) infoItem = null else selectedItem = null
                        },
                        images = fullscreenImages,
                        onPrevious = {
                            if (index > 0) selectedItem = visibleItems[index - 1]
                        },
                        onNext = {
                            if (index in 0 until visibleItems.lastIndex) {
                                selectedItem = visibleItems[index + 1]
                            }
                        },
                        onDelete = { requestDelete(listOf(current)) },
                        onMove = { requestMove(listOf(current)) },
                        onInfo = { infoItem = current },
                        canMove = hasAnyMoveDestination,
                        busy = actionState.running,
                    )
                }

                pendingDelete?.let { items ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = {
                            Text(
                                if (items.size == 1) "Delete this photo?" else "Delete ${items.size} photos?",
                            )
                        },
                        text = {
                            Text(
                                "This removes the item(s) from their current source. " +
                                    "This cannot be undone.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    pendingDelete = null
                                    performDelete(items)
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                        },
                    )
                }

                pendingMove?.let { items ->
                    MoveDestinationDialog(
                        destinations = moveDestinationsFor(items),
                        onDismiss = { pendingMove = null },
                        onSelect = { destinationId ->
                            pendingMove = null
                            performMove(items, destinationId)
                        },
                    )
                }

                if (showNoMoveDestination) {
                    AlertDialog(
                        onDismissRequest = { showNoMoveDestination = false },
                        title = { Text("No move destination") },
                        text = {
                            Text(
                                "Connect at least one other Drive, MEGA, or NAS source, " +
                                    "then choose it when you move.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showNoMoveDestination = false }) {
                                Text("Got it")
                            }
                        },
                    )
                }

                if (infoItem != null) {
                    MediaInfoPanel(
                        state = infoPanelState,
                        onDismiss = { infoItem = null },
                    )
                }
            }
        }
    }
}
