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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pincatcher.R
import com.pincatcher.ui.capture.CaptureScreen
import com.pincatcher.ui.home.HomeScreen
import com.pincatcher.ui.inspector.InspectorScreen
import com.pincatcher.ui.settings.SettingsScreen
import com.pincatcher.ui.theme.Token

/**
 * Four destinations, no top bar.
 *
 * Each screen carries its own heading, so a persistent app bar would repeat the
 * title on every page. The nav is a plain label row rather than icons-plus-labels:
 * at four items the labels are what disambiguate them, and the icon set is not
 * worth the bytes.
 */
private enum class Tab(val labelRes: Int) {
    HOME(R.string.nav_home),
    CAPTURE(R.string.nav_capture),
    INSPECTOR(R.string.nav_inspector),
    SETTINGS(R.string.nav_settings),
}

@Composable
fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

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
                Tab.HOME -> HomeScreen()
                Tab.CAPTURE -> CaptureScreen()
                Tab.INSPECTOR -> InspectorScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
