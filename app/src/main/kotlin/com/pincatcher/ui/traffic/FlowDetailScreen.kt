package com.pincatcher.ui.traffic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pincatcher.data.Flow
import com.pincatcher.ui.component.EmptyState
import com.pincatcher.ui.component.ScreenHeading
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token

/**
 * Per-flow detail, after Reqable's detail tabs.
 *
 * The rule worth copying: **a tab that hides itself when it has nothing in it.**
 * Reqable omits Query on a request with no query parameters, Cookie when there
 * are none. That keeps the strip short and never shows an empty pane, which on a
 * phone is the difference between four tabs and nine.
 *
 * Overview and Body are always present - they are the reason the screen exists.
 * Raw2 is Reqable's other good idea: the decoded protocol message and the
 * untouched bytes side by side, which is the only way to debug a compression bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowDetailScreen(flow: Flow, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tabs = remember(flow.id) { detailTabsFor(flow) }
    var selected by remember(flow.id) { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = flow.host,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to traffic")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = selected.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)),
                edgePadding = Token.SpaceOuter,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selected,
                        onClick = { selected = index },
                        text = { Text(tab.title) },
                    )
                }
            }

            when (val tab = tabs.getOrNull(selected)) {
                null -> EmptyState(
                    title = "Nothing to show",
                    body = "This flow has no stored detail.",
                    modifier = Modifier.fillMaxSize(),
                )

                else -> when (tab) {
                    DetailTab.Overview -> OverviewTab(flow)
                    DetailTab.Query -> TextTab(flow.query ?: "No query string.")
                    DetailTab.Headers -> HeadersTab(flow)
                    DetailTab.Body -> BodyTab(flow)
                    DetailTab.Raw -> TextTab(rawMessage(flow, decoded = true))
                    DetailTab.Raw2 -> TextTab(rawMessage(flow, decoded = false))
                    DetailTab.Patch -> PatchTab(flow)
                }
            }
        }
    }
}

private enum class DetailTab(val title: String) {
    Overview("Overview"),
    Query("Query"),
    Headers("Headers"),
    Body("Body"),
    Raw("Raw"),
    Raw2("Raw2"),
    Patch("Patch"),
}

/** Only the tabs that have something in them. Headers and Body are always there. */
private fun detailTabsFor(flow: Flow): List<DetailTab> = buildList {
    add(DetailTab.Overview)
    if (!flow.query.isNullOrBlank()) add(DetailTab.Query)
    add(DetailTab.Headers)
    add(DetailTab.Body)
    add(DetailTab.Raw)
    add(DetailTab.Raw2)
    add(DetailTab.Patch)
}

@Composable
private fun OverviewTab(flow: Flow) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Token.SpaceOuter),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        ScreenHeading(
            title = "${flow.method} ${flow.path}",
            subtitle = flow.url,
        )
        HorizontalDivider()
        KeyValue("Status", flow.statusCode?.toString() ?: "—", StatusColor.forStatus(flow.statusCode))
        KeyValue("Scheme", flow.scheme)
        KeyValue("Source app", flow.appPackage ?: "unknown")
        KeyValue("Started", java.text.DateFormat.getDateTimeInstance().format(flow.startTime))
        KeyValue("Duration", flow.durationMs?.let { "$it ms" } ?: "—")
        KeyValue("Request size", flow.reqSize?.let { "$it B" } ?: "—")
        KeyValue("Response size", flow.resSize?.let { "$it B" } ?: "—")
        flow.error?.let { KeyValue("Error", it, StatusColor.forStatus(400)) }
        HorizontalDivider()
        Text(
            text = "Remote address and TLS detail appear once the tunnel records the " +
                "upstream connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeadersTab(flow: Flow) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Token.SpaceOuter),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        Text("Request", style = MaterialTheme.typography.titleSmall)
        Text(
            text = flow.reqHeaders ?: "No request headers recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Response", style = MaterialTheme.typography.titleSmall)
        Text(
            text = flow.resHeaders ?: "No response headers recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Body viewer. Reqable ships JSON, XML, hex and image viewers under one tab; the
 * sub-viewer is picked from the content type once bodies are actually stored.
 */
@Composable
private fun BodyTab(flow: Flow) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Token.SpaceOuter),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        Text(
            text = "Request body",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = bodyState(flow.reqBodyHash),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Response body", style = MaterialTheme.typography.titleSmall)
        Text(
            text = bodyState(flow.resBodyHash),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The PinCatcher-specific tab. A generic proxy cannot show this: it has no idea
 * what was changed in the client app to make this flow visible at all.
 */
@Composable
private fun PatchTab(flow: Flow) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Token.SpaceOuter),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceInner),
    ) {
        ScreenHeading(
            title = flow.appPackage ?: "Unknown app",
            subtitle = "What had to change for this traffic to be readable",
        )
        HorizontalDivider()
        PatchLine("Certificate trust", "Pending - the CA wizard has not run.")
        PatchLine("Network security config", "Pending - the APK patcher has not run.")
        PatchLine("Pinning stubs", "Pending.")
        PatchLine("Flutter engine", flow.appPackage?.let { "Not checked." } ?: "—")
        HorizontalDivider()
        Text(
            text = "Each line turns into a real verdict once capture, certificate " +
                "installation and APK patching are wired up. Right now none of them " +
                "can be true.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PatchLine(label: String, state: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = state,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextTab(content: String) {
    Text(
        text = content,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .padding(Token.SpaceOuter),
    )
}

@Composable
private fun KeyValue(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceBase),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun bodyState(hash: String?): String = when {
    hash == null -> "Not captured."
    else -> "Stored, ${hash.take(12)}… - the viewer lands with body persistence."
}

/**
 * Reqable's Raw/Raw2 pair: the same protocol message with the body decoded, and
 * with the bytes untouched. Comparing the two is the only way to tell a
 * compression bug from a decoding bug.
 */
private fun rawMessage(flow: Flow, decoded: Boolean): String {
    if (!decoded && flow.reqBodyHash == null && flow.resBodyHash == null) {
        return "Nothing captured on the wire yet."
    }
    return buildString {
        append(flow.method).append(' ').append(flow.path)
        flow.query?.let { append('?').append(it) }
        append(" HTTP/1.1\r\n")
        append(flow.reqHeaders ?: "")
        append("\r\n\r\n")
        append(if (decoded) "<decoded body>" else "<raw body bytes>")
    }
}