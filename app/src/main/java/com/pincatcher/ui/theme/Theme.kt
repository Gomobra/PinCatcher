package com.pincatcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF00696E)
private val TealDark = Color(0xFF4FD8E0)
private val Amber = Color(0xFF7A5900)

private val LightScheme = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A6365),
    tertiary = Amber,
)

private val DarkScheme = darkColorScheme(
    primary = TealDark,
    secondary = Color(0xFFB1CBD0),
    tertiary = Color(0xFFF2BF48),
)

@Composable
fun PinCatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand identity beats wallpaper theming here: colour carries meaning in
    // the flow list (status classes), so it must not shift with the device.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
