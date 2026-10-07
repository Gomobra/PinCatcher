package com.pincatcher.ui.home

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.pincatcher.apk.InstalledApp
import com.pincatcher.apk.InstalledApps
import com.pincatcher.ui.component.EmptyState
import com.pincatcher.ui.component.Pill
import com.pincatcher.ui.component.PressableRow
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.component.SkeletonRow
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token

/**
 * Macrostructure: **Workbench**. Left-aligned, controls on top, dense list
 * below - not a centred column of cards.
 *
 * This screen works today. `ApplicationInfo.sourceDir` is readable by any app,
 * so listing and launching needs no root. Extraction is Phase 3, and the row
 * says so rather than pretending a tap does something it cannot.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(includeSystem) {
        val result = InstalledApps.list(context, includeSystem)
        // An empty result and a refused permission look identical from here, so
        // treat empty-with-a-query-typed differently rather than guessing.
        loadFailed = result.isEmpty() && !includeSystem
        apps = result
    }

    val visible = remember(apps, query) {
        val needle = query.trim()
        apps.orEmpty().filter {
            needle.isEmpty() ||
                it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
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
            Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
                Text("Choose a target", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "PinCatcher reads the APK an app already has on disk. " +
                        "Uninstalling it later is not required to inspect it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter by name or package") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Token.SpaceInner)) {
                FilterChip(
                    selected = !includeSystem,
                    onClick = { includeSystem = false },
                    label = { Text("User apps") },
                )
                FilterChip(
                    selected = includeSystem,
                    onClick = { includeSystem = true },
                    label = { Text("Include system") },
                )
            }
        }

        SectionRule()

        when {
            apps == null -> Column(Modifier.fillMaxSize()) {
                repeat(6) { SkeletonRow() }
            }

            visible.isEmpty() && loadFailed -> EmptyState(
                title = "No apps returned",
                body = "Android did not hand over the installed-app list. Check the " +
                    "package-visibility permission for PinCatcher in system settings, " +
                    "then try \"Include system\".",
                modifier = Modifier.fillMaxSize(),
            )

            visible.isEmpty() -> EmptyState(
                title = "No match",
                body = "Nothing is called \"$query\". Clear the filter to see all apps.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Token.SpaceInner),
                verticalArrangement = Arrangement.spacedBy(Token.SpaceTight),
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        modifier = Modifier.padding(horizontal = Token.SpaceOuter),
                        onClick = { InstalledApps.launch(context, app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressableRow(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(Token.SpaceBase),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Token.SpaceBase),
        ) {
            val icon = remember(app.packageName) { app.icon?.toBitmap() }
            if (icon != null) {
                Box(Modifier.size(Token.ListIconSize)) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(Token.ListIconSize),
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(Token.ListIconSize)
                        .padding(Token.SpaceInner),
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${app.versionName} (${app.versionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (app.isSplit) {
                // A split APK has to be merged before it can be patched, which
                // is worth surfacing before the user hits a wall later.
                Pill(text = "split", color = StatusColor.tertiary())
            }
        }
    }
}

private fun android.graphics.drawable.Drawable.toBitmap(): android.graphics.Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else 96
    val h = if (intrinsicHeight > 0) intrinsicHeight else 96
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}