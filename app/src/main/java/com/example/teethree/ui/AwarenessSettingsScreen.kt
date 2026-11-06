package com.example.teethree.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.teethree.awareness.AgentPushTokens
import com.example.teethree.awareness.AwarenessStore
import com.example.teethree.awareness.SubscriptionUsageSnapshot
import com.example.teethree.awareness.SubscriptionUsageWidget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwarenessSettingsScreen(
    onBack: () -> Unit,
    onPreferencesChanged: () -> Unit,
    registrationStatus: String? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { AwarenessStore(context) }
    var preferences by remember { mutableStateOf(store.read()) }
    var permission by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val configured = remember(context) { AgentPushTokens.configured(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
                permission = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = NotificationManagerCompat.from(context).areNotificationsEnabled()
            onPreferencesChanged()
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications and widgets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                if (permission) "Notifications are allowed on this device."
                else "Allow notifications to receive agent alerts and ongoing activity."
            )
            if (!permission && Build.VERSION.SDK_INT >= 33)
                Button(
                    onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                ) {
                    Text("Allow notifications")
                }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            ) {
                Text("Open Android notification settings")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Agent alerts", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Approvals, questions, completed work and failures.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = preferences.notificationsEnabled,
                    onCheckedChange = {
                        preferences = preferences.copy(notificationsEnabled = it)
                        store.save(preferences)
                        onPreferencesChanged()
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Ongoing agent activity", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A quiet card for current work, with Open and Dismiss actions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = preferences.ongoingEnabled,
                    onCheckedChange = {
                        preferences = preferences.copy(ongoingEnabled = it)
                        store.save(preferences)
                        onPreferencesChanged()
                    },
                )
            }
            Text(
                if (!configured)
                    "Push notifications are not configured for this app build. T3 Connect and a matching Firebase configuration are required for delivery while the app is closed."
                else
                    registrationStatus
                        ?: "Sign into T3 Connect to register this device for agent updates.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Subscription Usage widget", style = MaterialTheme.typography.titleMedium)
            Text(
                "Codex and Claude limits refresh while teethree is connected. After 15 minutes or a quota reset, the widget asks you to open the app to refresh.",
                style = MaterialTheme.typography.bodySmall,
            )
            val manager = remember(context) { AppWidgetManager.getInstance(context) }
            if (manager.isRequestPinAppWidgetSupported)
                OutlinedButton(
                    onClick = {
                        manager.requestPinAppWidget(
                            ComponentName(context, SubscriptionUsageWidget::class.java),
                            null,
                            null,
                        )
                    }
                ) {
                    Text("Add subscription widget")
                }
            else
                Text(
                    "Add Subscription Usage from your home screen’s widget picker.",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionUsageScreen(
    snapshot: SubscriptionUsageSnapshot,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDetails: (() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscription usage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (snapshot.providers.isEmpty())
                Text("Connect to an environment with Codex or Claude to see subscription limits.")
            snapshot.providers.forEach { provider ->
                Text(provider.name, style = MaterialTheme.typography.titleLarge)
                if (provider.expiresAt <= now && provider.windows.isNotEmpty())
                    Text("Open a connected environment and refresh to update these limits.")
                else {
                    Text(provider.detail, style = MaterialTheme.typography.bodySmall)
                    provider.windows.forEach { window ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${window.label} · ${window.remaining}% left")
                            LinearProgressIndicator(
                                progress = { window.remaining / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text(window.reset, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onRefresh) { Text("Refresh limits") }
            onDetails?.let { open ->
                TextButton(onClick = open) { Text("Cost, tokens, and usage history") }
            }
        }
    }
}
