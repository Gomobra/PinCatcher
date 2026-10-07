package com.pincatcher.ui.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pincatcher.data.Flow
import com.pincatcher.ui.component.StatusDot
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token

/**
 * One captured exchange, in the dense form Reqable uses: a spreadsheet row that
 * is scannable in one glance rather than a card.
 *
 * The dot is pinned to the far left and never moves, same as every other proxy -
 * the eye learns the position once. What the dot means is the HTTP class here
 * instead of Reqable's complete/failed/in-progress, which is more useful when
 * you are reading a list of results.
 */
@Composable
fun TrafficRow(
    flow: Flow,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Token.RowMinHeight)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
            )
            .padding(horizontal = Token.SpaceOuter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        StatusDot(
            color = StatusColor.forStatus(flow.statusCode),
            modifier = Modifier.clearAndSetSemantics {},
        )

        Text(
            text = flow.method,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(Token.ColumnMethod),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )

        Text(
            // The status code is text, not just colour, so the row survives a
            // colour-blind reader and a greyscale screenshot.
            text = flow.statusCode?.toString() ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColor.forStatus(flow.statusCode),
            textAlign = TextAlign.End,
            modifier = Modifier.width(Token.ColumnStatus),
            maxLines = 1,
        )

        Column(Modifier.weight(1f)) {
            Text(
                text = flow.host,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = flow.path + (flow.query?.let { "?$it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Differentiation: the package that produced this flow, and the patch
        // state it is in. A generic proxy cannot show this - it has no idea what
        // is on the other end of the socket.
        ProvenanceBadge(flow.appPackage, flow.appUid)
    }
}

/**
 * Compact app identity. Two lines of text rather than an icon: at 56 dp row
 * height an icon costs more vertical space than the label is worth, and the
 * label is what a user scans for.
 */
@Composable
private fun ProvenanceBadge(packageName: String?, uid: Int?) {
    val label = when {
        packageName != null -> packageName.substringAfterLast('.')
        uid != null -> "uid $uid"
        else -> "—"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(64.dp),
    )
}

