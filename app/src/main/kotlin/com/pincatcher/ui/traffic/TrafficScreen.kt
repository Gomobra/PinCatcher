package com.pincatcher.ui.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pincatcher.data.Flow
import com.pincatcher.data.FlowStore
import com.pincatcher.data.PincatcherDatabase
import com.pincatcher.ui.component.EmptyState
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.theme.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The main content surface, after Reqable's Traffic tab.
 *
 * Four things lifted from it: the search bar over the whole list, a sortable
 * column header, the dense row, and a bottom bar carrying live counts - total,
 * filtered, selected. That last one is the piece none of the other three tools
 * has, because all three are desktop tools with disk to spare.
 */
@Composable
fun TrafficScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortKey.TimeDesc) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var openFlow by remember { mutableStateOf<Flow?>(null) }

    val store = remember { FlowStore(PincatcherDatabase(context)) }

    val all: List<Flow>? by produceState<List<Flow>?>(initialValue = null, store) {
        value = withContext(Dispatchers.IO) { store.recent(SESSION_ANY, limit = 500) }
    }

    // FTS4 MATCH is not what a user means by "app.v2", so FlowStore quotes the
    // term and this behaves as a literal search over the index.
    val rows: List<Flow>? by produceState<List<Flow>?>(
        initialValue = null, store, query, sort,
    ) {
        value = withContext(Dispatchers.IO) {
            val base = if (query.isBlank()) {
                store.recent(SESSION_ANY, limit = 500)
            } else {
                runCatching { store.search(query, limit = 500) }.getOrDefault(emptyList())
            }
            sort.applyTo(base)
        }
    }

    // Detail is a full-screen push rather than Reqable's resizable split pane:
    // a phone has no room for both at a readable density.
    val detail = openFlow
    if (detail != null) {
        FlowDetailScreen(flow = detail, onBack = { openFlow = null })
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Token.SpaceOuter)
                .padding(top = Token.SpaceBase),
            singleLine = true,
            label = { Text("Search URL and headers") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )

        Spacer(Modifier.height(Token.SpaceInner))
        SectionRule()
        ColumnHeaderRow(sort = sort, onSortChange = { sort = it })
        SectionRule()

        val list = rows
        when {
            list == null -> EmptyState(
                title = "Reading",
                body = "Querying the flow table.",
                modifier = Modifier.fillMaxSize(),
            )

            list.isEmpty() && query.isNotBlank() -> EmptyState(
                title = "No match",
                body = "Nothing in the flow table contains \"$query\". The search covers " +
                    "the URL and both header sets.",
                modifier = Modifier.fillMaxSize(),
            )

            list.isEmpty() -> EmptyState(
                title = "No traffic recorded",
                body = "The capture tunnel is not reading the tun device yet, so this list " +
                    "is empty by design rather than by failure.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = Token.SpaceHairline),
            ) {
                items(list, key = { it.id }) { flow ->
                    TrafficRow(
                        flow = flow,
                        selected = flow.id == selectedId,
                        onClick = {
                            // First tap selects, second tap on the same row opens.
                            // On a phone, double-tap is not in the gesture
                            // vocabulary most users reach for.
                            if (flow.id == selectedId) openFlow = flow else selectedId = flow.id
                        },
                    )
                }
            }
        }

        TrafficStatusBar(
            total = all?.size ?: 0,
            filtered = list?.size ?: 0,
            hasSelection = selectedId != null,
        )
    }
}

internal enum class SortKey(val label: String) {
    TimeDesc("Time"),
    StatusAsc("Status"),
    HostAsc("Host"),
}

private fun SortKey.applyTo(flows: List<Flow>): List<Flow> = when (this) {
    SortKey.TimeDesc -> flows.sortedByDescending { it.startTime }
    SortKey.StatusAsc -> flows.sortedBy { it.statusCode ?: Int.MAX_VALUE }
    SortKey.HostAsc -> flows.sortedBy { it.host }
}

/**
 * Sortable column header, after Reqable's.
 *
 * On a phone the visible columns are fixed - horizontal scrolling would fight
 * the vertical scroll - so the header carries the three that usefully order,
 * and everything else moves to the detail pane. Reqable's version lets you
 * show, hide, reorder and drag-resize columns; that is a mouse affordance.
 */
@Composable
private fun ColumnHeaderRow(sort: SortKey, onSortChange: (SortKey) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Token.ColumnHeaderHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Token.SpaceOuter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        Box(Modifier.width(Token.StatusDotSize))
        Text(
            text = "Method",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(Token.ColumnMethod),
        )
        HeaderCell(
            label = "Status",
            width = Token.ColumnStatus,
            alignEnd = true,
            active = sort == SortKey.StatusAsc,
            onClick = { onSortChange(if (sort == SortKey.StatusAsc) SortKey.TimeDesc else SortKey.StatusAsc) },
        )
        HeaderCell(
            label = "Host and path",
            weight = true,
            active = sort == SortKey.HostAsc,
            onClick = { onSortChange(if (sort == SortKey.HostAsc) SortKey.TimeDesc else SortKey.HostAsc) },
        )
        Text(
            text = "App",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(56.dp),
        )
        HeaderCell(
            label = "Age",
            width = Token.ColumnMethod,
            active = sort == SortKey.TimeDesc,
            onClick = { onSortChange(if (sort == SortKey.TimeDesc) SortKey.HostAsc else SortKey.TimeDesc) },
        )
    }
}

@Composable
private fun RowScope.HeaderCell(
    label: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    weight: Boolean = false,
    alignEnd: Boolean = false,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = if (active) "$label ▾" else label,
        style = MaterialTheme.typography.labelSmall,
        // The sorted column takes the accent so the active order is obvious
        // without a separate arrow icon.
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = if (alignEnd) TextAlign.End else null,
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (weight) Modifier.weight(1f) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    )
}

/** Reqable's bottom bar: total, filtered, selected. */
@Composable
private fun TrafficStatusBar(total: Int, filtered: Int, hasSelection: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Token.SpaceOuter, vertical = Token.SpaceInner),
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceBase),
    ) {
        Text(
            text = "$total total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$filtered shown",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasSelection) {
            Text(
                text = "1 selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Placeholder until sessions are wired to the capture engine. */
internal const val SESSION_ANY = 0L