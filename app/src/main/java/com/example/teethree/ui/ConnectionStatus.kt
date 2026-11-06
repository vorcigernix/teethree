package com.example.teethree.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The link to the desktop as the user sees it. Connecting is the normal state on every launch and
 * after the phone wakes, so it stays quiet: one line under the title and a ring around the host.
 * Only after [UnreachableAfterMs] does the line offer Retry and help.
 */
enum class LinkPhase {
    Online,
    /** Opening (or reopening) the link. Everything stays usable and is sent once connected. */
    Connecting,
    /** Connected; catching up on what the agents did meanwhile. Usually under a second. */
    Syncing,
    /** Still not connected after [UnreachableAfterMs], or nothing left to retry on its own. */
    Unreachable;

    val offline: Boolean
        get() = this == Connecting || this == Unreachable
}

internal const val UnreachableAfterMs = 30_000L

internal fun linkPhase(
    status: ConnectionStatus,
    offlineSince: Long?,
    now: Long,
    syncing: Boolean = false,
): LinkPhase =
    when {
        status == ConnectionStatus.Connected -> if (syncing) LinkPhase.Syncing else LinkPhase.Online
        // Nothing left to retry on its own (access revoked, environment gone).
        status == ConnectionStatus.Disconnected -> LinkPhase.Unreachable
        offlineSince == null || now - offlineSince < UnreachableAfterMs -> LinkPhase.Connecting
        else -> LinkPhase.Unreachable
    }

/** What the repository and view model know about the link, without the ticking clock. */
@Immutable
internal data class LinkInfo(
    val status: ConnectionStatus = ConnectionStatus.Connected,
    val host: String = "desktop",
    val address: String? = null,
    val attempts: Int = 0,
    val offlineSince: Long? = null,
    val lastLiveAt: Long? = null,
    val nextRetryAt: Long? = null,
    /** Connected, but the first snapshot of threads hasn't arrived yet. */
    val syncing: Boolean = false,
    val queued: Int = 0,
    val running: Int = 0,
    val waiting: Int = 0,
    val error: String? = null,
)

/**
 * The link as the UI sees it. The clock lives here, not in the caller, so only the views that
 * show elapsed time recompose while it ticks.
 */
@Stable
internal class LinkUi(retry: () -> Unit, help: () -> Unit) {
    var info by mutableStateOf(LinkInfo())
        internal set

    var now by mutableLongStateOf(System.currentTimeMillis())
        internal set

    var retry: () -> Unit = retry
        internal set

    var help: () -> Unit = help
        internal set

    val phase: LinkPhase
        get() = linkPhase(info.status, info.offlineSince, now, info.syncing)
}

internal val LocalLink = compositionLocalOf<LinkUi?> { null }

@Composable
internal fun rememberLinkUi(info: LinkInfo, retry: () -> Unit, help: () -> Unit): LinkUi {
    val link = remember { LinkUi(retry, help) }
    SideEffect {
        link.info = info
        link.retry = retry
        link.help = help
    }
    LaunchedEffect(link) {
        snapshotFlow { link.info.status == ConnectionStatus.Connected }
            .collectLatest { live ->
                link.now = System.currentTimeMillis()
                while (!live) {
                    delay(1_000)
                    link.now = System.currentTimeMillis()
                }
            }
    }
    return link
}

internal fun elapsedShort(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 ->
            (seconds / 60).let { m -> if (m < 5 && seconds % 60 != 0L) "$m m ${seconds % 60} s" else "$m m" }
        seconds < 86_400 -> "${seconds / 3600} h"
        else -> "${seconds / 86_400} d"
    }
}

private fun plural(count: Int, one: String, many: String = one + "s") =
    "$count ${if (count == 1) one else many}"

/** The words on the status line, or null when it should show its normal content. */
internal fun statusLineText(phase: LinkPhase, host: String): String? =
    when (phase) {
        LinkPhase.Online -> null
        LinkPhase.Connecting -> "Connecting to $host…"
        LinkPhase.Syncing -> "Syncing…"
        LinkPhase.Unreachable -> "Can't reach $host"
    }

/**
 * One quiet line under a title. On launch it first shows the saved screen's age ("Updated 2 h
 * ago") with no indicator; only after [IndicatorHoldMs] does a small spinner and "Connecting to
 * devbox…" fade in, in the same grey as other secondary text. After 30 s it reads "Can't reach
 * devbox · Retry", and tapping it opens the help sheet. Once live it crossfades back to
 * [content], the normal subtitle, at the same height.
 */
@Composable
internal fun ConnectionStatusLine(
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val link = LocalLink.current
    if (link == null) {
        content?.invoke()
        return
    }
    val phase = link.phase
    val waiting = rememberLinkWaiting()
    // Syncing only shows when the wait was visible; a fast connect goes straight to normal.
    var waited by remember { mutableStateOf(false) }
    if (waiting) waited = true
    if (phase == LinkPhase.Online) waited = false
    val savedAt = link.info.lastLiveAt
    val line: StatusLine =
        when {
            phase == LinkPhase.Online -> StatusLine.Normal
            phase == LinkPhase.Syncing -> if (waited) StatusLine.Syncing else StatusLine.Normal
            waiting -> if (phase == LinkPhase.Unreachable) StatusLine.Unreachable else StatusLine.Connecting
            savedAt != null -> StatusLine.Saved
            else -> StatusLine.Normal
        }
    val reduced = rememberReducedMotion()
    AnimatedContent(
        line,
        modifier.testTag(if (line == StatusLine.Normal) "status-normal" else "connection-status"),
        transitionSpec = {
            (fadeIn(tween(if (reduced) 150 else 250, easing = EmphasizedDecelerate)) togetherWith
                fadeOut(tween(90))) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.CenterStart,
        label = "status-line",
    ) { shown ->
        when (shown) {
            StatusLine.Normal -> content?.invoke() ?: Spacer(Modifier.height(16.dp))
            StatusLine.Saved ->
                Text(
                    "Updated ${elapsedShort(link.now - (savedAt ?: link.now))} ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            else -> {
                val unreachable = shown == StatusLine.Unreachable
                Row(
                    if (unreachable)
                        Modifier.clickable(onClickLabel = "Connection help", role = Role.Button) {
                            link.help()
                        }
                    else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        Modifier.size(11.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 1.5.dp,
                        trackColor = Color.Transparent,
                    )
                    Text(
                        when (shown) {
                            StatusLine.Syncing -> "Syncing…"
                            StatusLine.Unreachable -> "Can't reach ${link.info.host}"
                            else -> "Connecting to ${link.info.host}…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unreachable)
                        Text(
                            "· Retry",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                }
            }
        }
    }
}

private enum class StatusLine { Normal, Saved, Connecting, Syncing, Unreachable }

private val EmphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** The desktop as an avatar: a green dot when live, a thin progress ring while connecting. */
@Composable
internal fun HostIndicator(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val link = LocalLink.current
    val phase = link?.phase ?: LinkPhase.Online
    val host = link?.info?.host ?: "desktop"
    val state =
        when (phase) {
            LinkPhase.Online -> "connected"
            LinkPhase.Connecting -> "connecting"
            LinkPhase.Syncing -> "syncing"
            LinkPhase.Unreachable -> "can't reach it"
        }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.semantics { contentDescription = "$host, $state" },
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Dns,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val waiting = rememberLinkWaiting()
                val dot by animateColorAsState(
                    if (phase == LinkPhase.Online) T3Colors.success
                    else MaterialTheme.colorScheme.outline,
                    tween(250),
                    label = "host-dot",
                )
                if (!waiting)
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 6.dp)
                            .size(12.dp)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .background(dot, CircleShape)
                            .testTag("host-dot")
                    )
                androidx.compose.animation.AnimatedVisibility(
                    waiting,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                ) {
                    CircularProgressIndicator(
                        Modifier.size(44.dp).testTag("host-ring"),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
        if (label != null)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
    }
}

/** How this phone itself is connected, for telling a phone problem from a desktop problem. */
internal data class PhoneNetwork(val online: Boolean, val kind: String)

@Composable
private fun rememberPhoneNetwork(): PhoneNetwork {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    fun read(): PhoneNetwork {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return PhoneNetwork(false, "No network")
        val kind =
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Network"
            }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && kind != "VPN"
        return PhoneNetwork(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            if (vpn) "$kind · VPN" else kind,
        )
    }
    var network by remember { mutableStateOf(read()) }
    DisposableEffect(manager) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    network = read()
                }

                override fun onLost(n: Network) {
                    network = read()
                }

                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    network = read()
                }
            }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return network
}

@Composable
private fun DiagnosticRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String,
    divider: Boolean,
) {
    Column {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Diagnostics for an outage: is it this phone, the desktop, or the path between them? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionHelpSheet(
    onDismiss: () -> Unit,
    onSwitchHost: () -> Unit,
    onRePair: () -> Unit,
) {
    val link = LocalLink.current ?: return
    val info = link.info
    val now = link.now
    val phone = rememberPhoneNetwork()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Closes on its own once the link is back.
    val phase = link.phase
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(phase) { if (!phase.offline) dismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .testTag("connection-help"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(48.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (phone.online) Icons.Outlined.CloudOff else Icons.Outlined.WifiOff,
                        null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    if (phone.online) "Can't reach ${info.host}" else "This phone is offline",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Your agents keep running on the desktop. Nothing is lost — this phone syncs " +
                        "as soon as it's back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column {
                    DiagnosticRow(
                        if (phone.online) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        if (phone.online) T3Colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        if (phone.online) "This phone is online" else "This phone has no internet",
                        phone.kind,
                        divider = false,
                    )
                    val since = info.offlineSince
                    DiagnosticRow(
                        Icons.Outlined.MoreHoriz,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "Waiting for ${info.host}",
                        listOfNotNull(
                                info.address,
                                info.error?.takeIf { info.status == ConnectionStatus.Disconnected },
                                if (info.attempts > 0 && since != null)
                                    "${plural(info.attempts, "try", "tries")} in ${elapsedShort(now - since)}"
                                else null,
                            )
                            .joinToString(" · ")
                            .ifEmpty { "Waiting for a reply" },
                        divider = true,
                    )
                    DiagnosticRow(
                        Icons.Outlined.History,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        info.lastLiveAt?.let { "Last seen ${elapsedShort(now - it)} ago" }
                            ?: "Not seen since the app opened",
                        listOfNotNull(
                                info.running.takeIf { it > 0 }?.let { plural(it, "agent") + " running" },
                                info.waiting.takeIf { it > 0 }?.let { "$it waiting for you" },
                            )
                            .joinToString(" · ")
                            .ifEmpty { "No agents were running" },
                        divider = true,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (phone.online)
                        "Is the desktop asleep, or on a different network? Keep T3 Code running " +
                            "and allow it through the firewall."
                    else "Turn on Wi-Fi or mobile data. Queued messages go out once you're back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = link.retry,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Retry now")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onSwitchHost,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Switch host")
                    }
                    FilledTonalButton(
                        onClick = onRePair,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Re-pair")
                    }
                }
            }
            Text(
                info.nextRetryAt?.let { "Auto-retrying · next in ${elapsedShort(it - now + 999)}" }
                    ?: "Trying now…",
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
