package com.pincatcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pincatcher.R
import com.pincatcher.ui.apps.AppsScreen
import com.pincatcher.ui.capture.CaptureScreen
import com.pincatcher.ui.settings.SettingsScreen
import com.pincatcher.ui.theme.Token
import com.pincatcher.ui.traffic.TrafficScreen

/**
 * Bottom navigation, ordered by what the user does first.
 *
 * Capture leads because it is the only thing that produces traffic, and Traffic
 * follows because it is where they look next - the same order Reqable's desktop
 * puts its Traffic tab in. Apps sits third because choosing a target is setup
 * rather than the daily loop.
 *
 * No top bar: each screen carries its own heading, so a persistent app bar would
 * repeat the title on every page.
 */
private enum class Tab(val labelRes: Int) {
    CAPTURE(R.string.nav_capture),
    TRAFFIC(R.string.nav_traffic),
    APPS(R.string.nav_apps),
    SETTINGS(R.string.nav_settings),
}

/**
 * The apps the next capture will include. Empty means every app.
 *
 * Hoisted here because two screens read it and one writes it: Apps picks,
 * Capture previews and starts. Saved across configuration changes because losing
 * a ten-app selection to a rotation is the kind of thing that makes a picker feel
 * broken - but deliberately not persisted past the process, since it configures
 * the next capture rather than describing anything already recorded.
 *
 * Empty rather than "all selected" is the default state, so the first run
 * captures everything. A picker with nothing ticked reads as broken; a picker with
 * two hundred ticked rows reads as noise.
 */
@Composable
private fun rememberCaptureTargets(): SnapshotStateList<String> = rememberSaveable(
    // Second type argument is what goes in the Bundle, so it is String, not the
    // list: restore gets back a List<String> and rebuilds the observable wrapper.
    saver = listSaver<SnapshotStateList<String>, String>(
        save = { ArrayList(it) },
        restore = { saved -> saved.toMutableStateList() },
    ),
) { mutableStateListOf() }

@Composable
fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.CAPTURE) }
    val targets = rememberCaptureTargets()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = Token.ElevationNone,
            ) {
                Tab.entries.forEach { entry ->
                    val selected = tab == entry
                    val label = stringResource(entry.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(label) },
                        modifier = Modifier
                            .height(Token.TouchTarget)
                            .semantics { contentDescription = label },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.CAPTURE -> CaptureScreen(targets = targets)
                Tab.TRAFFIC -> TrafficScreen()
                Tab.APPS -> AppsScreen(
                    targets = targets,
                    onCaptureSelected = { tab = Tab.CAPTURE },
                )

                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}