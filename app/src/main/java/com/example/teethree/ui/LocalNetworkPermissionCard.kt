package com.example.teethree.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.teethree.localNetworkPermissionGranted

@Composable
internal fun LocalNetworkPermissionCard() {
    if (Build.VERSION.SDK_INT < 37) return
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(localNetworkPermissionGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = localNetworkPermissionGranted(context)
    }
    if (granted) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local network access", style = MaterialTheme.typography.titleMedium)
            Text(
                "Allow access to connect to an environment on your local network.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    if (
                        requested &&
                            activity != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.ACCESS_LOCAL_NETWORK,
                            )
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        requested = true
                        launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                }
            ) {
                Text("Allow local network access")
            }
        }
    }
}
