package com.pincatcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pincatcher.core.data.db.BodyStore
import com.pincatcher.core.data.db.PincatcherDatabase
import com.pincatcher.core.domain.StoragePolicy
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.component.Stat
import com.pincatcher.ui.theme.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Macrostructure: **Grouped ledger** - unlike Home's left-aligned workbench,
 * this stacks label-over-value rows in a narrow column so the numbers line up.
 *
 * Every figure is read out of the database. Nothing here is estimated or
 * sampled, so an all-zero screen is a truthful report of an unused install.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { BodyStore(PincatcherDatabase(context)) }

    val usage: Usage? by produceState<Usage?>(initialValue = null, store) {
        value = withContext(Dispatchers.IO) {
            val stored = store.storedBytes()
            val saved = store.savedByDedupAndCompression()
            Usage(stored, saved)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Token.SpaceOuter)
            .padding(top = Token.SpaceDisplay, bottom = Token.SpaceSection),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceBase),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
            Text("Storage", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Ring-buffer limits are applied on every capture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionRule()

        // Uneven column widths on purpose: the value column is narrow because
        // the values are counts, and an even split reads as a template.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Token.SpaceSection),
        ) {
            Stat(
                label = "On disk",
                value = usage?.let { formatBytes(it.stored) } ?: "…",
                modifier = Modifier.weight(1.4f),
            )
            Stat(
                label = "Saved by dedup and compression",
                value = usage?.let { formatBytes(it.saved) } ?: "…",
                modifier = Modifier.weight(1.6f),
                caption = "Against ${formatBytes((usage?.stored ?: 0L) + (usage?.saved ?: 0L))} raw",
            )
        }

        SectionRule()

        Text("Ring buffer", style = MaterialTheme.typography.titleSmall)
        LimitRow("Maximum flows", StoragePolicy.DEFAULT_MAX_FLOWS.toString())
        LimitRow("Maximum size", formatBytes(StoragePolicy.DEFAULT_MAX_STORAGE_BYTES))
        LimitRow("Maximum age", "${StoragePolicy.DEFAULT_MAX_AGE_MS / 3_600_000} hours")

        SectionRule()

        Text("About", style = MaterialTheme.typography.titleSmall)
        LimitRow("Version", "0.1.0-alpha01")
        LimitRow("Build", "debug")
        Text(
            text = "Everything runs on this device. No telemetry, no account, no upload. " +
                "Use only on APKs you are authorised to analyse.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Token.SpaceInner),
        )
    }
}

@Composable
private fun LimitRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Token.SpaceTight),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class Usage(val stored: Long, val saved: Long)

/** Decimal MB, matching how the ring-buffer budget is expressed. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
