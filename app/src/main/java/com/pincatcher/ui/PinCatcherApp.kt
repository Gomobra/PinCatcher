package com.pincatcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pincatcher.R

private enum class Tab(val labelRes: Int) {
    HOME(R.string.nav_home),
    CAPTURE(R.string.nav_capture),
    INSPECTOR(R.string.nav_inspector),
    SETTINGS(R.string.nav_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinCatcherApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(text = when (tab) {
                Tab.HOME -> stringResource(R.string.home_pick_app)
                Tab.CAPTURE -> stringResource(R.string.capture_start)
                Tab.INSPECTOR -> stringResource(R.string.inspector_empty)
                Tab.SETTINGS -> stringResource(R.string.nav_settings)
            })
        }
    }
}
