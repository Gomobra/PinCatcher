package com.pincatcher.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pincatcher.ui.theme.Token

/**
 * Shared components. Each appears on more than one screen; anything used once
 * stays inline with its caller.
 *
 * Interactive pieces carry pressed / focused / disabled states rather than the
 * single default most Compose code bothers with, and every one of them clears
 * or supplies semantics so TalkBack does not read a decorative dot aloud.
 */

/** Status dot. Decorative: the status code text beside it is the real signal. */
@Composable
fun StatusDot(color: Color, size: Dp = Token.StatusDotSize, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clearAndSetSemantics {},
    )
}

/**
 * Empty state: what is missing, why, and what to do.
 *
 * [action] is omitted rather than faked when there is genuinely nothing the
 * user can do yet. "Nothing here" without a reason reads as a bug.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Token.SpaceOuter, vertical = Token.SpaceSection),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Token.IconSize),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

/**
 * Placeholder row shown while a list loads.
 *
 * A skeleton rather than a spinner: the content has a predictable shape (icon,
 * two lines), so showing that shape reserves the right amount of space and
 * avoids the layout jump a spinner causes when data lands.
 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val shimmer = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Token.SpaceOuter, vertical = Token.SpaceInner),
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceBase),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Token.ListIconSize)
                .clip(RoundedCornerShape(Token.CornerRadius))
                .background(shimmer),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(Token.SkeletonBarHeight)
                    .clip(RoundedCornerShape(Token.CornerRadius))
                    .background(shimmer),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.35f)
                    .height(Token.SkeletonBarHeightSmall)
                    .clip(RoundedCornerShape(Token.CornerRadius))
                    .background(shimmer),
            )
        }
    }
}

/**
 * A tappable row with pressed and focus feedback.
 *
 * Focus gets a visible ring rather than being cleared - Compose's default focus
 * indication is easy to lose and keyboard and switch-access users depend on it.
 * Pressed shifts the surface one step darker instead of moving the layout.
 */
@Composable
fun PressableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()

    val surface by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surface
            pressed -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(90),
        label = "rowSurface",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Token.CornerRadius))
            .background(surface)
            .border(
                width = Token.HairlineWidth,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(Token.CornerRadius),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

/**
 * A labelled number. Only ever rendered for a value that was actually read out
 * of the database - a dashboard showing a fabricated "1,284 requests" is worse
 * than no dashboard.
 */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Token.SpaceTight),
    ) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small status label, e.g. "split APK", "CA installed". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(Token.CornerRadius))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Token.SpaceInner, vertical = Token.SpaceTight)
            .clearAndSetSemantics {},
    )
}

/** Screen-level heading. Left-aligned on purpose - centring every heading is the template tell. */
@Composable
fun ScreenHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceTight),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Start,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Section rule. A hairline, not a shadow - depth here comes from tone. */
@Composable
fun SectionRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Token.HairlineWidth)
            .background(MaterialTheme.colorScheme.outline),
    )
}