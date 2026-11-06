package com.example.teethree.awareness

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.example.teethree.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

class AwarenessStore(context: Context) {
    private val app = context.applicationContext
    private val preferences =
        app.getSharedPreferences("agent-awareness-preferences", Context.MODE_PRIVATE)

    val deviceId: String
        get() =
            synchronized(this) {
                preferences.getString("deviceId", null)
                    ?: UUID.randomUUID().toString().also {
                        preferences.edit().putString("deviceId", it).apply()
                    }
            }

    fun read() =
        AwarenessPreferences(
            preferences.getBoolean("notifications", true),
            preferences.getBoolean("ongoing", true),
        )

    fun save(value: AwarenessPreferences) {
        preferences
            .edit()
            .putBoolean("notifications", value.notificationsEnabled)
            .putBoolean("ongoing", value.ongoingEnabled)
            .apply()
        val identity = preferences.getString("userId", null)
        if (identity != null)
            AgentNotifications.configure(
                app,
                deviceId,
                identity,
                "teethree",
                value.ongoingEnabled,
                value.notificationsEnabled,
            )
    }

    fun activate(userId: String) {
        require(userId.isNotBlank())
        preferences.edit().putString("userId", userId).apply()
        val settings = read()
        AgentNotifications.configure(
            app,
            deviceId,
            userId,
            "teethree",
            settings.ongoingEnabled,
            settings.notificationsEnabled,
        )
    }

    fun signOut() {
        preferences.edit().remove("userId").apply()
        AgentNotifications.clear(app)
    }

    suspend fun registration(): JsonObject {
        val token = AgentPushTokens.token(app)
        return relayDeviceRegistration(
            deviceId,
            Build.MODEL.ifBlank { "Android" },
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME,
            BuildConfig.APPLICATION_ID,
            token,
            read(),
            NotificationManagerCompat.from(app).areNotificationsEnabled(),
        )
    }
}

object AgentPushTokens {
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    fun configured(context: Context): Boolean = runCatching {
        FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
    }
        .getOrDefault(false)

    internal fun changed(context: Context, token: String) {
        context
            .getSharedPreferences("agent-push-token", Context.MODE_PRIVATE)
            .edit()
            .putString("token", token)
            .apply()
        mutableChanges.value += 1L
    }

    suspend fun token(context: Context): String? {
        if (!configured(context)) return null
        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (continuation.isActive)
                        continuation.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }
    }
}
