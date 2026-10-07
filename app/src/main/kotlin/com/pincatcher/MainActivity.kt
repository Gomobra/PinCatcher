package com.pincatcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pincatcher.ui.AppShell
import com.pincatcher.ui.theme.PinCatcherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PinCatcherTheme {
                AppShell()
            }
        }
    }
}
