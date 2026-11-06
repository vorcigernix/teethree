package com.example.teethree.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.*
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RichUsageScreen(
    repository: T3Repository,
    onBack: () -> Unit,
    onManageAccounts: (() -> Unit)? = null,
    configs: Map<String, JsonObject> = emptyMap(),
    environmentLabel: String = "Current environment",
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val connection = repository.connectionId
    val generation = repository.connectionGeneration
    val tools = remember(repository) { UsageTools(repository) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val diagnostics = connected && "diagnostics:read" in state.scopes
    var view by rememberSaveable(connection) { mutableStateOf("limits") }
    var days by rememberSaveable(connection) { mutableIntStateOf(30) }
    var refresh by remember(connection) { mutableIntStateOf(0) }
    var busy by remember(connection) { mutableStateOf(false) }
    var error by remember(connection) { mutableStateOf<String?>(null) }
    var status by remember(connection) { mutableStateOf<String?>(null) }
    var page by remember(connection) { mutableStateOf<Pair<Int, NativeUsageReport>?>(null) }
    val report = page?.second
    var refreshedProviders by remember(connection) { mutableStateOf<JsonArray?>(null) }
    var resetAccount by remember(connection) { mutableStateOf<NativeLimitAccount?>(null) }
    var cursorConfirmation by remember(connection) { mutableStateOf(false) }
    var failedAttempt by remember(connection) { mutableStateOf<String?>(null) }
    val totals =
        remember(report) {
            mergeNativeUsage(
                report
                    ?.let {
                        listOf(EnvironmentUsageReport(connection.orEmpty(), environmentLabel, it))
                    }
                    .orEmpty()
            )
        }
    val limitConfigs =
        remember(configs, state.serverConfig, refreshedProviders, connection) {
            configs.toMutableMap().apply {
                if (connection != null && state.serverConfig != null)
                    put(connection, state.serverConfig!!)
                if (connection != null && refreshedProviders != null)
                    put(
                        connection,
                        JsonObject(
                            get(connection).orEmpty() + ("providers" to refreshedProviders!!)
                        ),
                    )
            }
        }
    val limits = remember(limitConfigs) { collectNativeUsageLimits(limitConfigs) }
    val pools = remember(limits.accounts) { nativeLimitPools(limits.accounts) }
    val canReset = connected && "providers:manage" in state.scopes
    fun current() =
        repository.connectionId == connection && repository.connectionGeneration == generation
    fun action(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        status = null
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current()) error = failure.message ?: "Usage could not be refreshed."
            } finally {
                busy = false
            }
        }
    }
    fun openUsageLink(url: String) {
        safeUsageLink(url)?.let { safe ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, safe.toUri())) }
                .onFailure { error = "No app could open this usage page." }
        }
    }
    LaunchedEffect(state.serverConfig) { refreshedProviders = null }
    LaunchedEffect(
        connection,
        generation,
        connected,
        diagnostics,
        days,
        refresh,
        view == "limits",
    ) {
        if (!connected || connection == null) return@LaunchedEffect
        val attempt = "$days:$refresh:${view == "limits"}"
        if (failedAttempt == attempt) return@LaunchedEffect
        busy = true
        error = null
        try {
            if (view == "limits") {
                if ("orchestration:read" in state.scopes)
                    refreshedProviders = tools.refreshLimits().array("providers")
            } else if (diagnostics) {
                val window = nativeUsageWindow(days)
                if (refresh > 0) {
                    try {
                        tools.refreshRates()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (current())
                            status =
                                "Pricing could not be refreshed; showing the environment's available rates."
                    }
                }
                var latest = tools.summary(window)
                if (!current()) return@LaunchedEffect
                page = days to latest
                if (latest.refreshing) {
                    latest = tools.summary(window, awaitRefresh = true)
                    if (current()) page = days to latest
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current()) {
                failedAttempt = attempt
                error =
                    (failure.message ?: "This environment could not report usage.") +
                        " If this server predates Usage, update it to use this view."
            }
        } finally {
            busy = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(enabled = connected && !busy, onClick = { refresh += 1 }) {
                        Icon(Icons.Outlined.Refresh, "Refresh usage")
                    }
                },
            )
        }
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets).widthIn(max = 1100.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    if (view == "limits" && limitConfigs.size > 1)
                        "Subscription accounts across ${limitConfigs.size} saved environments"
                    else environmentLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cost" to "Cost", "tokens" to "Tokens", "limits" to "Limits").forEach {
                        (value, label) ->
                        FilterChip(
                            view == value,
                            { view = value },
                            label = { Text(label) },
                            enabled = !busy,
                        )
                    }
                }
                if (view != "limits")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1 to "24h", 7 to "7d", 30 to "30d", 90 to "90d").forEach {
                            (value, label) ->
                            FilterChip(
                                days == value,
                                { days = value },
                                label = { Text(label) },
                                enabled = !busy,
                            )
                        }
                    }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!connected)
                    Text(
                        "Connect to an environment to refresh usage. Saved subscription limits may be out of date."
                    )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            if (view == "limits") {
                if (pools.isNotEmpty())
                    item {
                        Text("Subscription remaining", style = MaterialTheme.typography.titleLarge)
                    }
                items(pools, key = { "pool:${it.driver}:${it.id}" }) { pool ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${usageDriverLabel(pool.driver)} · ${pool.label} · ${pool.remaining}% left"
                        )
                        LinearProgressIndicator(
                            progress = { pool.remaining / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        Text(
                            if (pool.accountCount > 1)
                                "${pool.accountCount} accounts · pooled average"
                            else "1 account",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (limits.accounts.isEmpty())
                    item {
                        Text(
                            "No subscription limits reported. Connect a supported provider account and refresh."
                        )
                    }
                items(limits.notices) { Text(it, style = MaterialTheme.typography.bodySmall) }
                item { Text("Accounts", style = MaterialTheme.typography.titleLarge) }
                items(limits.accounts, key = { "account:${it.key}" }) { account ->
                    UsageAccountCard(
                        account,
                        canReset && account.redeemEnvironmentId == connection && !busy,
                        onReset = { resetAccount = account },
                        onOpenUsage = ::openUsageLink,
                    )
                }
                if (onManageAccounts != null)
                    item {
                        OutlinedButton(onClick = onManageAccounts) {
                            Text("Manage provider accounts")
                        }
                    }
            } else {
                if (!diagnostics)
                    item {
                        Text(
                            "This connection needs diagnostics:read permission to read transcript usage."
                        )
                    }
                totals.warnings.forEach { warning ->
                    item {
                        Text(
                            warning,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (totals.duplicateSources.isNotEmpty())
                    item {
                        Text(
                            "Counted once across environments sharing a transcript directory: ${totals.duplicateSources.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                if (report != null && report!!.version in 4..NATIVE_USAGE_CONTRACT_VERSION) {
                    item {
                        Text(
                            if (view == "tokens") usageTokenText(totals.tokens.total)
                            else if (totals.records > 0 && totals.unpricedRecords >= totals.records)
                                "Cost unavailable"
                            else usageMoney(totals.cost),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            "${totals.records} records · ${totals.sessions} distinct sessions · ${if (page?.first == 1) "past 24h" else "${page?.first} calendar days"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (busy)
                            Text(
                                "Updating the selected window…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (totals.unpricedRecords > 0)
                            Text(
                                "${totals.unpricedRecords} records lack model prices. Cost totals include only priced usage.",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        Text(
                            "Pricing: ${report!!.pricing.string("status", "unavailable")} · ${report!!.pricing.long("knownModels")} known models",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (report!!.modelFilter != null)
                            Text(
                                "The environment's usage model filter is applied.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    item {
                        UsageHistoryChart(
                            if (page?.first == 1) totals.hourly else totals.daily,
                            view == "tokens",
                            report!!.timeZone,
                        )
                    }
                    item { Text("Providers", style = MaterialTheme.typography.titleLarge) }
                    items(totals.providers, key = { "provider:${it.key}" }) { provider ->
                        UsageBreakdownRow(
                            usageDriverLabel(provider.key),
                            provider,
                            view == "tokens",
                        )
                    }
                    item {
                        Text("Token mix", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Uncached input ${usageTokenText(totals.tokens.input)} · cache reads ${usageTokenText(totals.tokens.cacheRead)} · cache writes ${usageTokenText(totals.tokens.cacheWrite)} · output ${usageTokenText(totals.tokens.output)}"
                        )
                        Text(
                            "Reasoning ${usageTokenText(totals.tokens.reasoning)} is included in output.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (view == "cost")
                        item {
                            Text("Cost details", style = MaterialTheme.typography.titleLarge)
                            totals.categoryCosts.forEach { (category, cost) ->
                                Text("$category · ${usageMoney(cost)}")
                            }
                            Text("Estimated cache savings · ${usageMoney(totals.cacheSavings)}")
                            totals.speedCosts.forEach { (speed, cost) ->
                                if (cost != 0.0) Text("$speed · ${usageMoney(cost)}")
                            }
                            if (totals.records > 0)
                                Text(
                                    "${usagePercent(totals.reportedRecords, totals.records)} provider-reported · ${usagePercent((totals.records - totals.reportedRecords - totals.unpricedRecords).coerceAtLeast(0), totals.records)} model-priced · ${usagePercent(totals.unpricedRecords, totals.records)} unpriced",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                    item { Text("Models", style = MaterialTheme.typography.titleLarge) }
                    items(totals.models, key = { "model:${it.provider}:${it.key}" }) { model ->
                        UsageBreakdownRow(
                            "${usageDriverLabel(model.provider)} · ${model.key}",
                            model,
                            view == "tokens",
                        )
                    }
                    if (totals.records == 0L && !busy)
                        item { Text("No usage records in this window.") }
                    if (
                        report!!.sources.any { it.action == "enableCursorKeychain" } &&
                            state.serverConfig?.array("providers")?.any { raw ->
                                val provider = raw as? JsonObject
                                provider?.string("driver") == "cursor" &&
                                    provider.string("status") == "ready"
                            } == true
                    )
                        item {
                            Text(
                                "Cursor account usage requires access to your Cursor login in the environment's macOS Keychain."
                            )
                            OutlinedButton(
                                enabled = connected && !busy && "settings:write" in state.scopes,
                                onClick = { cursorConfirmation = true },
                            ) {
                                Text("Enable Cursor account usage")
                            }
                        }
                }
            }
            limits.externalLinks.forEach { (url, label) ->
                item { TextButton(onClick = { openUsageLink(url) }) { Text(label) } }
            }
        }
    }
    resetAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { if (!busy) resetAccount = null },
            title = { Text("Use a reset credit?") },
            text = {
                Text(
                    "This redeems one credit on your account and clears its current rate-limit windows. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && canReset && account.redeemEnvironmentId == connection,
                    onClick = {
                        action {
                            status = tools.consumeResetCredit(account)
                            resetAccount = null
                            refreshedProviders = tools.refreshLimits().array("providers")
                        }
                    },
                ) {
                    Text("Use credit")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { resetAccount = null }) { Text("Cancel") }
            },
        )
    }
    if (cursorConfirmation)
        AlertDialog(
            onDismissRequest = { cursorConfirmation = false },
            title = { Text("Enable Cursor usage?") },
            text = {
                Text(
                    "The environment will read your Cursor login from macOS Keychain to obtain account usage. macOS may ask you to allow access."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        action {
                            tools.enableCursorKeychain()
                            cursorConfirmation = false
                            refresh += 1
                        }
                    },
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { cursorConfirmation = false }) { Text("Cancel") }
            },
        )
}

@Composable
private fun UsageBreakdownRow(label: String, value: UsageBreakdown, tokens: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            if (tokens) usageTokenText(value.tokens.total)
            else if (value.costUnknown) "Unpriced" else usageMoney(value.cost)
        )
        Text(
            "${value.records} records${if (value.unpricedRecords > 0) " · ${value.unpricedRecords} unpriced" else ""}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UsageAccountCard(
    account: NativeLimitAccount,
    canReset: Boolean,
    onReset: () -> Unit,
    onOpenUsage: (String) -> Unit,
) {
    var expanded by rememberSaveable(account.key) { mutableStateOf(false) }
    var revealed by rememberSaveable(account.key) { mutableStateOf(false) }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(account.name, style = MaterialTheme.typography.titleMedium)
            account.email?.let { email ->
                TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) email else "••••••@•••••• · reveal account")
                }
            }
            Text(
                listOfNotNull(usageDriverLabel(account.driver), account.plan, account.sourceLabel)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Checked ${usageDate(account.checkedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide account limits" else "Show account limits")
            }
            if (expanded)
                effectiveNativeLimitWindows(account).forEach { window ->
                    Text("${window.label} · ${window.remaining}% left")
                    LinearProgressIndicator(
                        progress = { window.remaining / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    window.resetsAt?.let {
                        Text("Resets ${usageDate(it)}", style = MaterialTheme.typography.bodySmall)
                    }
                    nativeLimitPace(window)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            account.credits?.let { credit ->
                Text(
                    "${credit.available} reset credits banked${credit.nextExpiresAt?.let { " · next expires ${usageDate(it)}" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (credit.available > 0 && account.redeemInput != null)
                    TextButton(enabled = canReset, onClick = onReset) { Text("Use reset credit") }
                if (credit.available > 0 && !canReset)
                    Text(
                        "This connection cannot redeem this account's reset credits.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            account.externalUrl?.let { url ->
                TextButton(onClick = { onOpenUsage(url) }) { Text("Manage account usage") }
            }
        }
    }
}

@Composable
private fun UsageHistoryChart(periods: List<UsagePeriod>, tokens: Boolean, zone: String) {
    var selected by remember(periods) { mutableIntStateOf(periods.lastIndex.coerceAtLeast(0)) }
    val color = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val values = periods.map { if (tokens) it.tokens.toDouble() else it.cost }
    val max = values.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (periods.firstOrNull()?.key?.contains('T') == true) "Hourly usage"
            else "Daily usage",
            style = MaterialTheme.typography.titleLarge,
        )
        Canvas(
            Modifier.fillMaxWidth()
                .height(180.dp)
                .semantics {
                    contentDescription =
                        "Usage history. ${periods.size} periods. Exact values are available below."
                }
                .pointerInput(periods) {
                    detectTapGestures { point ->
                        if (periods.isNotEmpty())
                            selected =
                                ((point.x / size.width) * periods.size)
                                    .toInt()
                                    .coerceIn(periods.indices)
                    }
                }
        ) {
            if (max > 0 && periods.isNotEmpty()) {
                val width = size.width / periods.size
                values.forEachIndexed { index, value ->
                    val height = (value.coerceAtLeast(0.0) / max * size.height).toFloat()
                    drawRect(
                        if (index == selected) selectedColor else color,
                        Offset(index * width + 1f, size.height - height),
                        Size((width - 2f).coerceAtLeast(1f), height),
                    )
                }
            }
        }
        periods.getOrNull(selected)?.let { period ->
            Text(
                "${usagePeriodLabel(period.key, zone)} · ${if (tokens) usageTokenText(period.tokens) else usageMoney(period.cost)}"
            )
        }
        var table by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { table = !table }) {
            Text(if (table) "Hide history table" else "Show history table")
        }
        if (table)
            periods.forEach { period ->
                Text(
                    "${usagePeriodLabel(period.key, zone)} · ${if (tokens) usageTokenText(period.tokens) else usageMoney(period.cost)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

private fun usageMoney(value: Double) =
    NumberFormat.getCurrencyInstance()
        .apply { currency = Currency.getInstance("USD") }
        .format(value)

private fun usageTokenText(value: Long): String =
    when {
        value >= 1_000_000_000 -> "%.2fB tokens".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.2fM tokens".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fK tokens".format(value / 1_000.0)
        else -> "${NumberFormat.getIntegerInstance().format(value)} tokens"
    }

private fun usagePercent(count: Long, total: Long) =
    "%.1f%%".format(if (total > 0) count * 100.0 / total else 0.0)

private fun usageDriverLabel(value: String) =
    when (value) {
        "codex" -> "Codex"
        "claude",
        "claudeAgent" -> "Claude"
        "cursor" -> "Cursor"
        "opencode" -> "OpenCode"
        "antigravity" -> "Antigravity"
        "grok" -> "Grok"
        else -> value.replaceFirstChar { it.uppercase() }
    }

private fun usageDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}
    .getOrDefault(value)

private fun usagePeriodLabel(value: String, zone: String): String =
    if (value.contains('T'))
        runCatching {
                DateTimeFormatter.ofPattern("MMM d, HH:mm")
                    .withZone(ZoneId.of(zone))
                    .format(Instant.parse(value))
            }
            .getOrDefault(value)
    else value
