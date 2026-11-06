package com.example.teethree.awareness

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.teethree.MainActivity
import com.example.teethree.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SubscriptionUsageWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SubscriptionWidgetPublisher.render(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == EXPIRE) SubscriptionWidgetPublisher.render(context)
    }

    override fun onDisabled(context: Context) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(SubscriptionWidgetPublisher.expiryIntent(context))
    }

    companion object {
        internal const val EXPIRE = "com.example.teethree.USAGE_WIDGET_EXPIRE"
    }
}

object SubscriptionWidgetPublisher {
    private val json = Json { ignoreUnknownKeys = true }

    fun cachedSnapshot(context: Context): SubscriptionUsageSnapshot {
        val stored =
            context
                .getSharedPreferences("subscription-widget", Context.MODE_PRIVATE)
                .getString("snapshot", null)
        return stored?.let {
            runCatching { json.decodeFromString<SubscriptionUsageSnapshot>(it) }.getOrNull()
        } ?: SubscriptionUsageSnapshot()
    }

    fun publish(context: Context, snapshot: SubscriptionUsageSnapshot) {
        val preferences = context.getSharedPreferences("subscription-widget", Context.MODE_PRIVATE)
        val serialized = json.encodeToString(snapshot)
        if (preferences.getString("snapshot", null) == serialized) return
        preferences.edit().putString("snapshot", serialized).apply()
        render(context)
    }

    fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids =
            manager.getAppWidgetIds(ComponentName(context, SubscriptionUsageWidget::class.java))
        if (ids.isEmpty()) return
        val stored =
            context
                .getSharedPreferences("subscription-widget", Context.MODE_PRIVATE)
                .getString("snapshot", null)
        val snapshot = stored?.let {
            runCatching { json.decodeFromString<SubscriptionUsageSnapshot>(it) }.getOrNull()
        }
        val providers =
            snapshot?.providers
                ?: listOf("Codex", "Claude").map {
                    UsageProvider(it, "Open teethree to connect", emptyList(), 0L, 0)
                }
        val now = System.currentTimeMillis()
        val views = RemoteViews(context.packageName, R.layout.subscription_usage_widget)
        views.removeAllViews(R.id.widget_providers)
        providers.forEach { provider ->
            val section = RemoteViews(context.packageName, R.layout.subscription_usage_provider)
            section.setTextViewText(R.id.widget_provider_name, provider.name)
            section.removeAllViews(R.id.widget_windows)
            val stale = provider.windows.isNotEmpty() && provider.expiresAt <= now
            val windows = if (stale) emptyList() else provider.windows.take(2)
            section.setTextViewText(
                R.id.widget_provider_detail,
                if (stale) "Open teethree to refresh" else provider.detail,
            )
            section.setViewVisibility(
                R.id.widget_provider_detail,
                if (windows.isEmpty()) View.VISIBLE else View.GONE,
            )
            windows.forEach { window ->
                val row = RemoteViews(context.packageName, R.layout.subscription_usage_window)
                row.setTextViewText(
                    R.id.widget_window_label,
                    "${window.label} · ${window.remaining}% left",
                )
                row.setTextColor(
                    R.id.widget_window_label,
                    ContextCompat.getColor(
                        context,
                        if (window.remaining <= 10) R.color.widget_danger
                        else R.color.widget_foreground,
                    ),
                )
                row.setProgressBar(
                    R.id.widget_window_progress,
                    100,
                    window.remaining.coerceIn(0, 100),
                    false,
                )
                row.setTextViewText(R.id.widget_window_reset, window.reset)
                section.addView(R.id.widget_windows, row)
            }
            val extra = if (stale) 0 else (provider.totalWindows - 2).coerceAtLeast(0)
            section.setTextViewText(R.id.widget_provider_extra, "$extra more in teethree")
            section.setViewVisibility(
                R.id.widget_provider_extra,
                if (extra > 0) View.VISIBLE else View.GONE,
            )
            views.addView(R.id.widget_providers, section)
        }
        views.setTextViewText(
            R.id.widget_checked_at,
            if (snapshot != null && snapshot.checkedAt > 0)
                "As of ${formatUsageTime(snapshot.checkedAt)}"
            else "Tap to connect in teethree",
        )
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("teethree://settings/usage"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                73002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        manager.updateAppWidget(ids, views)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val next = providers.map(UsageProvider::expiresAt).filter { it > now }.minOrNull()
        if (next == null) alarm.cancel(expiryIntent(context))
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, expiryIntent(context))
    }

    internal fun expiryIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            73002,
            Intent(context, SubscriptionUsageWidget::class.java)
                .setAction(SubscriptionUsageWidget.EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
