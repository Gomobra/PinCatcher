package com.pincatcher.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for colour and spacing.
 *
 * Nothing outside this file may name a raw colour or a raw dp. Components
 * reference tokens by name, so a palette change is one edit and an inline
 * hex value in a composable is a bug.
 *
 * Every foreground/background pair below was checked with the WCAG 2.1
 * relative-luminance formula against the surface it is actually rendered on.
 * Worst case across the whole set is 5.94:1, above the 4.5:1 body-text floor.
 * Re-run the check when a value changes; the numbers are in each comment.
 *
 * ## Why the status colours exist separately
 *
 * A traffic list reads as a set of signals first and text second, so the
 * semantic colours cannot be derived from the brand hue. Green/red is also the
 * single most common colour-blind failure, so [Status] is never the only
 * carrier of meaning: the status code itself is always rendered as text next
 * to the dot.
 */
object Token {

    // ---- Surfaces -------------------------------------------------------
    // Cool near-white, tinted toward the teal anchor hue. Not #fff.
    val SurfaceLight = Color(0xFFF7F8F8)
    val SurfaceElevatedLight = Color(0xFFFFFFFF)
    val SurfaceSunkenLight = Color(0xFFEDF0F0)
    val SurfaceDark = Color(0xFF121416)
    val SurfaceElevatedDark = Color(0xFF1A1D20)
    val SurfaceSunkenDark = Color(0xFF0C0E10)

    // ---- Ink ------------------------------------------------------------
    val InkLight = Color(0xFF191C1E)          // 16.10:1 on SurfaceLight
    val InkMutedLight = Color(0xFF5A6068)     //  5.97:1
    val InkDark = Color(0xFFE8EBED)            // 15.42:1 on SurfaceDark
    val InkMutedDark = Color(0xFF9BA2A9)       //  7.15:1

    // ---- Accent ---------------------------------------------------------
    // One hue, used for the active nav item, primary actions and focus only.
    val AccentLight = Color(0xFF0F6E70)
    val OnAccentLight = Color(0xFFFFFFFF)      //  6.03:1 on AccentLight
    val AccentDark = Color(0xFF4FD8E0)
    val OnAccentDark = Color(0xFF06282A)       //  9.10:1 on AccentDark

    // ---- Borders --------------------------------------------------------
    val BorderLight = Color(0xFFD3D8D9)
    val BorderStrongLight = Color(0xFF9AA2A6)
    val BorderDark = Color(0xFF2E3337)
    val BorderStrongDark = Color(0xFF545C62)

    // ---- Status ---------------------------------------------------------
    // Contrast measured against SurfaceLight / SurfaceDark respectively.
    object Status {
        val SuccessLight = Color(0xFF1B6C3A)      // 6.07:1
        val SuccessDark = Color(0xFF7BD8A0)       // 10.70:1
        val RedirectLight = Color(0xFF7A5B00)     // 5.94:1
        val RedirectDark = Color(0xFFEFC65C)      // 11.34:1
        val FailureLight = Color(0xFFA3231E)      // 7.01:1
        val FailureDark = Color(0xFFFF9E96)       // 9.30:1
        val UnknownLight = Color(0xFF5A6068)
        val UnknownDark = Color(0xFF9BA2A9)
    }

    /** Warning/accent for the amber chip family. */
    val TertiaryLight = Color(0xFF7A5900)        // 6.06:1
    val TertiaryDark = Color(0xFFF2BF48)         // 10.83:1

    // ---- Spacing --------------------------------------------------------
    // 4pt scale, named by role rather than by size so call sites read as
    // intent. Never type a raw dp in a composable.
    val SpaceHairline = 2.dp   // inside a single component
    val SpaceTight = 4.dp      // between a label and its value
    val SpaceInner = 8.dp      // between siblings in a compact group
    val SpaceBase = 12.dp      // list row padding
    val SpaceOuter = 16.dp     // screen gutter
    val SpaceSection = 24.dp   // between sections
    val SpaceDisplay = 40.dp   // above a screen's first heading

    // ---- Size -----------------------------------------------------------
    /** Stroke weight for rules and borders. Distinct from spacing: this is ink. */
    val HairlineWidth = 1.dp
    /** Empty-state icon. */
    val IconSize = 24.dp
    /** Skeleton placeholder bars, primary and secondary line. */
    val SkeletonBarHeight = 14.dp
    val SkeletonBarHeightSmall = 12.dp
    /** Surfaces are flat; hierarchy comes from tone, not elevation. */
    val ElevationNone = 0.dp

    /** Touch-target floor. Anything interactive is at least this tall. */
    val TouchTarget = 48.dp
    val ListIconSize = 40.dp
    val StatusDotSize = 8.dp

    val CornerRadius = 8.dp
}

/** Material scheme built from [Token] only. */
val PinCatcherLightScheme = lightColorScheme(
    primary = Token.AccentLight,
    onPrimary = Token.OnAccentLight,
    primaryContainer = Token.SurfaceSunkenLight,
    onPrimaryContainer = Token.InkLight,
    secondary = Token.InkMutedLight,
    onSecondary = Token.SurfaceElevatedLight,
    background = Token.SurfaceLight,
    onBackground = Token.InkLight,
    surface = Token.SurfaceLight,
    onSurface = Token.InkLight,
    surfaceVariant = Token.SurfaceSunkenLight,
    onSurfaceVariant = Token.InkMutedLight,
    surfaceContainerLow = Token.SurfaceElevatedLight,
    surfaceContainer = Token.SurfaceElevatedLight,
    surfaceContainerHigh = Token.SurfaceSunkenLight,
    outline = Token.BorderLight,
    outlineVariant = Token.BorderStrongLight,
    error = Token.Status.FailureLight,
    onError = Token.OnAccentLight,
    tertiary = Token.TertiaryLight,
    onTertiary = Token.OnAccentLight,
)

val PinCatcherDarkScheme = darkColorScheme(
    primary = Token.AccentDark,
    onPrimary = Token.OnAccentDark,
    primaryContainer = Token.SurfaceElevatedDark,
    onPrimaryContainer = Token.InkDark,
    secondary = Token.InkMutedDark,
    onSecondary = Token.SurfaceDark,
    background = Token.SurfaceDark,
    onBackground = Token.InkDark,
    surface = Token.SurfaceDark,
    onSurface = Token.InkDark,
    surfaceVariant = Token.SurfaceSunkenDark,
    onSurfaceVariant = Token.InkMutedDark,
    surfaceContainerLow = Token.SurfaceElevatedDark,
    surfaceContainer = Token.SurfaceElevatedDark,
    surfaceContainerHigh = Color(0xFF22262A),
    outline = Token.BorderDark,
    outlineVariant = Token.BorderStrongDark,
    error = Token.Status.FailureDark,
    onError = Token.SurfaceDark,
    tertiary = Token.TertiaryDark,
    onTertiary = Token.SurfaceDark,
)