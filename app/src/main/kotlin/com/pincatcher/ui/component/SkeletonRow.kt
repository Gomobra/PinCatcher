package com.pincatcher.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
