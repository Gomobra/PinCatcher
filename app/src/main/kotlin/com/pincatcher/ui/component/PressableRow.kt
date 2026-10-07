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
import com.pincatcher.ui.theme.Token

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
