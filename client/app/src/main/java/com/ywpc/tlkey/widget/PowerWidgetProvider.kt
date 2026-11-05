package com.ywpc.tlkey.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PowerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { id ->
            PowerWidgetStore(context).remove(id)
            alarm(context, id)?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
        }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) ->
            val store = PowerWidgetStore(context)
            store.write(new, store.read(old).copy(selected = "", pendingUntil = 0, resetAt = 0, message = "点击按钮控车", updatedAt = 0))
            store.remove(old)
            render(context, new)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (exists(context, id)) render(context, id)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ywpc.tlkey.widget.REFRESH"
        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PowerWidgetProvider::class.java))
        fun exists(context: Context, id: Int) = id in ids(context)
        fun refreshAll(context: Context) { ids(context).forEach { render(context, it) } }

        fun valid(context: Context, state: PowerWidgetState): Boolean {
            val app = context.applicationContext as App
            return state.vehicleId.isNotBlank() && state.ownerUid.isNotBlank() &&
                app.loginManager.hasSession() && app.authRepository.currentUid() == state.ownerUid &&
                app.vehicleRepository.find(state.vehicleId) != null
        }

        fun render(context: Context, id: Int) {
            if (!exists(context, id)) return
            val store = PowerWidgetStore(context)
            val original = store.read(id)
            var state = original.reconcile(System.currentTimeMillis())
            val valid = valid(context, state)
            if (!valid) state = state.copy(selected = "", resetAt = 0, pendingUntil = 0,
                message = if (state.vehicleId.isBlank()) "点此选择车辆" else "请打开 TL-key 重新配置")
            if (state != original) store.write(id, state)
            val app = context.applicationContext as App
            val vehicle = if (valid) app.vehicleRepository.find(state.vehicleId) else null
            val views = RemoteViews(context.packageName, R.layout.widget_power)
            views.setTextViewText(R.id.widget_vehicle, vehicle?.let {
                "${it.name.ifBlank { "我的台铃" }} · ${it.id.takeLast(4)}"
            } ?: "选择车辆 ›")
            views.setTextViewText(R.id.widget_status, state.message)
            views.setTextViewText(R.id.widget_time, if (state.updatedAt == 0L) "操作反馈 · 非实时车况" else
                "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.updatedAt))} · 操作反馈")
            val defaultColor = ContextCompat.getColor(context, R.color.widget_icon_default)
            val blue = ContextCompat.getColor(context, R.color.widget_blue)
            views.setInt(R.id.widget_on_icon, "setColorFilter", if (state.selected == PowerWidgetState.ON) blue else defaultColor)
            views.setInt(R.id.widget_off_icon, "setColorFilter", if (state.selected == PowerWidgetState.OFF) blue else defaultColor)
            views.setBoolean(R.id.widget_on, "setEnabled", state.pendingUntil == 0L)
            views.setBoolean(R.id.widget_off, "setEnabled", state.pendingUntil == 0L)
            views.setOnClickPendingIntent(R.id.widget_on, activity(context, id, PowerWidgetState.ON))
            views.setOnClickPendingIntent(R.id.widget_off, activity(context, id, PowerWidgetState.OFF))
            views.setOnClickPendingIntent(R.id.widget_vehicle, activity(context, id, "configure"))
            views.setOnClickPendingIntent(R.id.widget_status, activity(context, id, if (valid) "open" else "configure"))
            views.setOnClickPendingIntent(R.id.widget_title, activity(context, id, "open"))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
            val deadline = if (state.pendingUntil != 0L) state.pendingUntil else state.resetAt
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = alarm(context, id)!!
            if (deadline > 0) {
                // Process-independent fallback. Interactive 60s reset is also handled by the service.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
            } else alarms.cancel(pending)
        }

        private fun activity(context: Context, id: Int, action: String): PendingIntent {
            val target = when (action) {
                "configure" -> PowerWidgetConfigureActivity::class.java
                "open" -> MainActivity::class.java
                else -> PowerWidgetActionActivity::class.java
            }
            val intent = Intent(context, target).apply {
                data = Uri.parse("tlkey-widget://$id/$action")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra("power_action", action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun alarm(context: Context, id: Int): PendingIntent? = PendingIntent.getBroadcast(
            context, id, Intent(context, PowerWidgetProvider::class.java).apply {
                action = ACTION_REFRESH; data = Uri.parse("tlkey-widget://$id/reset")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
