package com.pincatcher.ui.capture

import android.content.Context
import android.net.VpnService
import android.security.KeyChain
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pincatcher.ui.component.SectionRule
import com.pincatcher.ui.theme.StatusColor
import com.pincatcher.ui.theme.Token

/**
 * Macrostructure: **Instrument panel**. Two real checks at the top, then a
 * plainly-worded statement of what is not built yet. Deliberately not a big
 * "Start capture" button that does nothing.
 *
 * Both checks are live: `VpnService.prepare` reflects whether consent has been
 * granted, and the CA query asks the system whether our root is in the user
 * store.
 */
@Composable
fun CaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val vpnConsentGranted: Boolean? by produceState<Boolean?>(initialValue = null, context) {
        value = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
    }
    val caInstalled: Boolean? = caStoreState()
    var lastCheck by remember { mutableStateOf(System.currentTimeMillis()) }

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
            // null = still checking, which is a real state and reads as one.
            done = vpnConsentGranted == true,
            pending = vpnConsentGranted == null,
            body = if ( vpnConsentGranted == true) {
                "Granted. Android will let PinCatcher route traffic through a local tunnel."
            } else {
                "Not granted yet. Android asks once, the first time capture starts."
            },
        )

        CheckRow(
            icon = Icons.Default.CheckCircle,
            title = "Root CA in the user store",
            done = caInstalled == true,
            pending = caInstalled == null,
            body = when (caInstalled) {
                true -> "Installed. HTTPS can be decrypted for apps that trust the user store."
                false -> "Not installed. Android only trusts user-added CAs for apps whose " +
                    "network security config allows it, which is what the APK patcher fixes."

                null -> "Cannot be checked yet - Android exposes no way to list the user " +
                    "CA store. The install wizard lands in Phase 2 and will answer this."
            },
        )

        SectionRule()

        Column(verticalArrangement = Arrangement.spacedBy(Token.SpaceInner)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Token.StatusDotSize * 2),
                )
                Text(
                    text = "  The tunnel is not wired up yet",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = "The packet codec and the TCP state machine are written and tested, " +
                    "but nothing reads the tun device yet, so no traffic is recorded. " +
                    "Treat this tab as a status panel until it says otherwise.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    lastCheck = System.currentTimeMillis()
                },
                enabled = true,
            ) {
                Text("Re-check now")
            }
            Text(
                text = "Last checked ${java.text.DateFormat.getTimeInstance().format(lastCheck)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

/**
 * Whether a root CA of ours is in the system user store.
 *
 * There is no public API for enumerating the user CA store, so before the
 * certificate wizard exists this cannot be answered at all. Returning null
 * makes the UI show "Checking…" rather than a confident "No", which would be a
 * guess dressed up as a fact.
 *
 * Once `CertWizard` lands, this reads our alias out of the KeyChain after
 * `KeyChain.createInstallIntent()` reports success.
 */
private fun caStoreState(): Boolean? = null