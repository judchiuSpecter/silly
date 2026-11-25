package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photohub.shared.db.MediaItem
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal enum class BulkDateMode { BEFORE, AFTER }
internal enum class BulkSizeMode { SMALLER, LARGER }
internal enum class BulkTypeFilter { ANY, PHOTO, VIDEO }

/**
 * Pure predicate behind the bulk-select dialog. Every non-empty rule must match
 * (AND), so a broad query can be narrowed without surprises.
 */
internal fun matchesBulkQuery(
    item: MediaItem,
    date: LocalDate? = null,
    dateMode: BulkDateMode? = null,
    sizeMb: Double? = null,
    sizeMode: BulkSizeMode? = null,
    sourceId: String? = null,
    nameContains: String = "",
    type: BulkTypeFilter = BulkTypeFilter.ANY,
    syncState: String? = null,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    if (date != null && dateMode != null) {
        if (item.dateTaken <= 0L) return false
        val taken = Instant.fromEpochMilliseconds(item.dateTaken)
            .toLocalDateTime(zone).date
        when (dateMode) {
            BulkDateMode.BEFORE -> if (taken >= date) return false
            BulkDateMode.AFTER -> if (taken <= date) return false
        }
    }
    if (sizeMb != null && sizeMode != null) {
        val threshold = sizeMb * 1_000_000L
        when (sizeMode) {
            BulkSizeMode.SMALLER -> if (item.sizeBytes >= threshold) return false
            BulkSizeMode.LARGER -> if (item.sizeBytes <= threshold) return false
        }
    }
    if (sourceId != null && item.sourceId != sourceId) return false
    if (nameContains.isNotBlank() &&
        !item.displayName.contains(nameContains, ignoreCase = true)
    ) return false
    when (type) {
        BulkTypeFilter.ANY -> Unit
        BulkTypeFilter.PHOTO -> if (item.isVideo == 1L) return false
        BulkTypeFilter.VIDEO -> if (item.isVideo != 1L) return false
    }
    if (syncState != null && item.syncState != syncState) return false
    return true
}

/** Selects every item matching the rules, replacing the current selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BulkSelectDialog(
    items: List<MediaItem>,
    sourceNames: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var dateMode by remember { mutableStateOf(BulkDateMode.BEFORE) }
    var dateText by remember { mutableStateOf("") }
    var sizeMode by remember { mutableStateOf(BulkSizeMode.SMALLER) }
    var sizeText by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var nameText by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(BulkTypeFilter.ANY) }
    var syncFilter by remember { mutableStateOf<String?>(null) }

    val date = remember(dateText) { runCatching { LocalDate.parse(dateText.trim()) }.getOrNull() }
    val dateInvalid = dateText.isNotBlank() && date == null
    val sizeMb = remember(sizeText) { sizeText.trim().toDoubleOrNull() }
    val sizeInvalid = sizeText.isNotBlank() && (sizeMb == null || sizeMb <= 0.0)
    val invalid = dateInvalid || sizeInvalid

    val sources = remember(items, sourceNames) {
        items.map { it.sourceId to (sourceNames[it.sourceId] ?: it.sourceId) }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
    val syncStates = remember(items) {
        items.map { it.syncState }.distinct().sorted()
    }

    val matched = if (invalid) {
        emptyList()
    } else {
        items.filter {
            matchesBulkQuery(
                item = it,
                date = date,
                dateMode = dateMode,
                sizeMb = sizeMb,
                sizeMode = sizeMode,
                sourceId = sourceFilter,
                nameContains = nameText.trim(),
                type = typeFilter,
                syncState = syncFilter,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select by rules") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Only filled-in rules are used. All rules must match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FieldLabel("Taken on")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = dateMode == BulkDateMode.BEFORE,
                        onClick = { dateMode = BulkDateMode.BEFORE },
                        label = { Text("Before") },
                    )
                    FilterChip(
                        selected = dateMode == BulkDateMode.AFTER,
                        onClick = { dateMode = BulkDateMode.AFTER },
                        label = { Text("After") },
                    )
                }
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = dateInvalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dateInvalid) {
                    Text(
                        "Enter a date as YYYY-MM-DD.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                FieldLabel("Size")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = sizeMode == BulkSizeMode.SMALLER,
                        onClick = { sizeMode = BulkSizeMode.SMALLER },
                        label = { Text("Smaller than") },
                    )
                    FilterChip(
                        selected = sizeMode == BulkSizeMode.LARGER,
                        onClick = { sizeMode = BulkSizeMode.LARGER },
                        label = { Text("Larger than") },
                    )
                }
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = { sizeText = it },
                    label = { Text("MB") },
                    singleLine = true,
                    isError = sizeInvalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sizeInvalid) {
                    Text(
                        "Enter a positive number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                FieldLabel("Source")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = sourceFilter == null,
                        onClick = { sourceFilter = null },
                        label = { Text("Any") },
                    )
                    for ((id, label) in sources) {
                        FilterChip(
                            selected = sourceFilter == id,
                            onClick = { sourceFilter = id },
                            label = { Text(label) },
                        )
                    }
                }

                FieldLabel("Name contains")
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("Type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (filter in BulkTypeFilter.entries) {
                        FilterChip(
                            selected = typeFilter == filter,
                            onClick = { typeFilter = filter },
                            label = {
                                Text(
                                    when (filter) {
                                        BulkTypeFilter.ANY -> "Any"
                                        BulkTypeFilter.PHOTO -> "Photos"
                                        BulkTypeFilter.VIDEO -> "Videos"
                                    },
                                )
                            },
                        )
                    }
                }

                if (syncStates.isNotEmpty()) {
                    FieldLabel("Sync state")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = syncFilter == null,
                            onClick = { syncFilter = null },
                            label = { Text("Any") },
                        )
                        for (state in syncStates) {
                            FilterChip(
                                selected = syncFilter == state,
                                onClick = { syncFilter = state },
                                label = { Text(syncLabel(state)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(matched.map { it.id }.toSet()) },
                enabled = !invalid,
            ) {
                Text("Select ${matched.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun syncLabel(state: String): String = when (state) {
    "INDEXED" -> "Indexed"
    "THUMB_CACHED" -> "Thumbnail cached"
    "ORIGINAL_CACHED" -> "Offline copy"
    else -> state.lowercase()
}
