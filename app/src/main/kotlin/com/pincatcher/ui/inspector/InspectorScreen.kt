package com.pincatcher.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pincatcher.data.Flow
import com.pincatcher.data.FlowStore
import com.pincatcher.data.PincatcherDatabase
import com.pincatcher.ui.component.EmptyState
import com.pincatcher.ui.component.PressableRow
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.component.StatusDot
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Macrostructure: **Dense ledger**. One row per exchange, no cards, no images -
 * the shape of the data *is* the design, the same way a log viewer should look.
 *
 * Reads the real database. The list is legitimately empty until the tunnel is
 * wired, and the empty state says that rather than showing sample rows.
 */
@Composable
fun InspectorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Flow>?>(null) }

    val store = remember { FlowStore(PincatcherDatabase(context)) }

    // Re-query as the user types. FTS4 MATCH syntax is not what a user means by
    // "app.v2", so FlowStore quotes the term.
    val flows: List<Flow>? by produceState<List<Flow>?>(initialValue = null, store, query) {
        value = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                store.recent(SESSION_ANY, limit = 200)
            } else {
                runCatching { store.search(query) }.getOrDefault(emptyList())
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = Token.SpaceOuter,
                end = Token.SpaceOuter,
                top = Token.SpaceDisplay,
                bottom = Token.SpaceBase,
            ),
            verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
        ) {
            Text("Inspector", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search URL and headers") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
        }

        SectionRule()

        when {
            flows == null -> EmptyState(
                title = "Reading",
                body = "Querying the flow table.",
                modifier = Modifier.fillMaxSize(),
            )

            flows!!.isEmpty() && query.isNotBlank() -> EmptyState(
                title = "No match",
                body = "Nothing in the flow table contains \"$query\". Search covers the URL " +
                    "and both header sets.",
                modifier = Modifier.fillMaxSize(),
            )

            flows!!.isEmpty() -> EmptyState(
                title = "No traffic recorded",
                body = "The capture tunnel is not reading the tun device yet, so the table " +
                    "is empty by design rather than by failure.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                results = flows
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Token.SpaceInner),
                    verticalArrangement = Arrangement.spacedBy(Token.SpaceHairline),
                ) {
                    items(results.orEmpty(), key = { it.id }) { flow ->
                        FlowRow(flow)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowRow(flow: Flow) {
    PressableRow(onClick = { /* detail pane is Phase 5 */ }) {
        Row(
            modifier = Modifier.padding(
                horizontal = Token.SpaceOuter,
                vertical = Token.SpaceInner,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Token.SpaceInner),
        ) {
            StatusDot(StatusColor.forStatus(flow.statusCode))

            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Token.SpaceInner)) {
                    Text(
                        text = flow.method,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // The code is rendered as text on purpose: the coloured
                        // dot alone would be a colour-only signal, which is the
                        // most common colour-blind failure there is.
                        text = flow.statusCode?.toString() ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColor.forStatus(flow.statusCode),
                    )
                }
                Text(
                    text = flow.host,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = flow.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            flow.durationMs?.let {
                Text(
                    text = "${it}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Placeholder until sessions are wired to the capture engine. */
private const val SESSION_ANY = 0L
