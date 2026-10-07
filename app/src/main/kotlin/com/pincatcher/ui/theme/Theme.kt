package com.pincatcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Is the UI currently in dark mode?
 *
 * Status colour has to differ per mode - the light-mode values do not reach
 * contrast on a dark surface - and `isSystemInDarkTheme` is a composable that
 * cannot be read from a plain helper. Threading it through a CompositionLocal
 * keeps [StatusColor] callable from any composable.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun PinCatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by design, and the default for a reason: colour carries meaning in a
    // traffic list (status class, capture state, anti-tamper risk), so it must
    // not shift with the user's wallpaper or a school device's forced palette.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> PinCatcherDarkScheme
        else -> PinCatcherLightScheme
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Colour for an HTTP status class, per mode. */
object StatusColor {

    @Composable
    fun forStatus(status: Int?): Color {
        val dark = LocalIsDarkTheme.current
        return when {
            status == null -> if (dark) Token.Status.UnknownDark else Token.Status.UnknownLight
            status in 200..299 -> if (dark) Token.Status.SuccessDark else Token.Status.SuccessLight
            status in 300..399 -> if (dark) Token.Status.RedirectDark else Token.Status.RedirectLight
            status in 400..499 -> if (dark) Token.Status.FailureDark else Token.Status.FailureLight
            else -> if (dark) Token.Status.UnknownDark else Token.Status.UnknownLight
        }
    }

    @Composable
    fun tertiary(): Color =
        if (LocalIsDarkTheme.current) Token.TertiaryDark else Token.TertiaryLight

    @Composable
    fun muted(): Color =
        if (LocalIsDarkTheme.current) Token.InkMutedDark else Token.InkMutedLight
}