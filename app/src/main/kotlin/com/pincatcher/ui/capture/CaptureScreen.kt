package com.pincatcher.ui.capture

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import com.pincatcher.capture.CaptureControl
import com.pincatcher.capture.CaptureVpnService
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.component.StatusDot
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token
import kotlinx.coroutines.delay

/**
 * Macrostructure: **instrument panel**. Two real checks at the top, then the
 * control, then what the tunnel is currently doing.
 *
 * Both checks are live. `VpnService.prepare` reflects whether consent has been
 * granted, and it is the only question Android will answer here - there is no API
 * for asking whether a capture of yours is running, so the counters come from the
 * service's own published state.
 */
@Composable
fun CaptureScreen(modifier: Modifier = Modifier, targets: List<String> = emptyList()) {
    val context = LocalContext.current

    // Bumped after the consent dialog resolves, so the check is asked again rather
    // than leaving a stale "not granted" sitting behind the button.
    var consentEpoch by remember { mutableIntStateOf(0) }
    val consentIntent: Intent? by produceState(initialValue = UNKNOWN, context, consentEpoch) {
        value = runCatching { VpnService.prepare(context) }.getOrNull()
    }

    // Consent is a one-time system dialog that cannot be requested headlessly. If
    // it is not in hand, ask for it; the launcher then starts the capture itself,
    // so there is no path where the dialog appears and nothing follows it.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) launchCapture(context, targets)
        consentEpoch++
    }

    var running by remember { mutableStateOf(false) }
    var packets by remember { mutableLongStateOf(0L) }
    var quicBlocked by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            running = CaptureControl.running
            packets = CaptureControl.packetsRead
            quicBlocked = CaptureControl.quicBlocked
            delay(COUNT_INTERVAL_MS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Token.SpaceOuter)
            .padding(top = Token.SpaceDisplay, bottom = Token.SpaceSection),
        verticalArrangement = Arrangement.spacedBy(Token.SpaceBase),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
            Text("Capture", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Two things have to be true before any HTTPS traffic can be read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionRule()

        CheckRow(
            icon = Icons.Default.Lock,
            title = "VPN tunnel consent",
            // Unknown = still checking, which is a real state and reads as one.
            done = consentIntent == null,
            pending = consentIntent == UNKNOWN,
            body = when (consentIntent) {
                null -> "Granted. Android will let PinCatcher route traffic through a local tunnel."
                UNKNOWN -> "Checking…"
                else -> "Not granted yet. Android asks once, the first time capture starts."
            },
        )

        CheckRow(
            icon = Icons.Default.CheckCircle,
            title = "Root CA in the user store",
            done = false,
            pending = true,
            body = "Cannot be checked yet - Android exposes no way to list the user CA store, and " +
                "the install wizard lands in Phase 2. Until then HTTPS bodies stay encrypted, " +
                "even though the flows themselves are recorded.",
        )

        SectionRule()

        // PRD 6.3's preview. Which apps the tunnel will carry has to be readable
        // here, on the button that starts it: an allow-list that quietly reverts
        // to all-apps records a device by surprise.
        Text(
            text = if (targets.isEmpty()) {
                "Scope: every app except PinCatcher."
            } else {
                "Scope: ${targets.size} app${if (targets.size == 1) "" else "s"}, " +
                    "everything else keeps its normal network."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (running) {
            Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceInner)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = StatusColor.forStatus(200))
                    Text("  Capturing", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = buildString {
                        append("$packets packets read")
                        if (quicBlocked > 0) append(" · $quicBlocked QUIC flows pushed down to TCP")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { CaptureControl.stop(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop")
                }
            }
        } else {
            Button(
                onClick = {
                    val prepare = consentIntent
                    if (prepare == null) launchCapture(context, targets) else consent.launch(prepare)
                },
                enabled = consentIntent != UNKNOWN,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (targets.isEmpty()) "Start capturing" else "Capture ${targets.size} app(s)")
            }
        }

        SectionRule()

        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceInner)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Token.StatusDotSize * 2),
                )
                Text("  What is on the wire right now", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = "Plaintext HTTP is recorded in full. HTTPS is recorded as a flow - host, " +
                    "path, timings, sizes - but its body stays encrypted until the CA wizard " +
                    "exists. QUIC is refused on purpose, so those requests fall back to TCP " +
                    "instead of disappearing from the capture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun launchCapture(context: android.content.Context, targets: List<String>) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_START)
            .putStringArrayListExtra(CaptureVpnService.EXTRA_TARGET_PACKAGES, ArrayList(targets)),
    )
}

/** "We have not asked yet", which is distinct from "the answer is no". */
private val UNKNOWN: Intent? = Intent("com.pincatcher.ui.UNKNOWN")

private const val COUNT_INTERVAL_MS = 1_000L

@Composable
private fun CheckRow(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean,
    pending: Boolean,
) {
    val stateColor: Color = when {
        pending -> StatusColor.muted()
        done -> StatusColor.forStatus(200)
        else -> StatusColor.forStatus(400)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Token.SpaceBase),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = stateColor,
            modifier = Modifier
                .size(Token.StatusDotSize * 2.5f)
                .semantics { contentDescription = if (done) "$title: done" else "$title: pending" },
        )
        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceTight)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = when {
                    pending -> "Checking…"
                    done -> "Ready"
                    else -> "Needed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}