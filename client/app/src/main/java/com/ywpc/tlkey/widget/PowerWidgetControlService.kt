package com.ywpc.tlkey.widget

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.IBinder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.control.ControlResult
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Foreground only while executing a user tap / completing its short feedback timer. */
class PowerWidgetControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var startupFailure: String? = null
    private val resets = mutableMapOf<Int, Job>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "桌面控车", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(NOTIFICATION, notification("正在处理桌面控车"))
        } catch (_: RuntimeException) {
            // Permission revocation / vendor restrictions must never open an Activity.
            startupFailure = "后台控车不可用，请检查蓝牙权限和应用后台设置"
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val action = intent?.getStringExtra("power_action")
        if (!PowerWidgetProvider.exists(this, id) || action !in listOf(PowerWidgetState.ON, PowerWidgetState.OFF)) {
            stopIfIdle(); return START_NOT_STICKY
        }
        if (operation?.isActive == true) {
            val store = PowerWidgetStore(this)
            val state = store.read(id)
            if (state.pendingUntil == 0L) {
                resets.remove(id)?.cancel()
                store.write(id, state.copy(selected = "", resetAt = 0,
                    message = "正在执行其他操作，请稍后重试", updatedAt = System.currentTimeMillis()))
                PowerWidgetProvider.render(this, id)
            }
            return START_NOT_STICKY
        }
        val store = PowerWidgetStore(this)
        val initial = store.read(id)
        if (!PowerWidgetProvider.valid(this, initial)) {
            PowerWidgetProvider.render(this, id); stopIfIdle(); return START_NOT_STICKY
        }
        val problem = startupFailure ?: readinessProblem(initial)
        if (problem != null) {
            resets.remove(id)?.cancel()
            store.write(id, initial.copy(selected = "", resetAt = 0, pendingUntil = 0,
                message = problem, updatedAt = System.currentTimeMillis()))
            PowerWidgetProvider.render(this, id)
            stopIfIdle()
            return START_NOT_STICKY
        }
        resets.remove(id)?.cancel()
        val token = UUID.randomUUID().toString()
        store.write(id, initial.begin(action!!, token, System.currentTimeMillis()))
        PowerWidgetProvider.render(this, id)
        // Dispatch normally so operation is assigned before its finally block runs.
        operation = scope.launch(start = kotlinx.coroutines.CoroutineStart.DEFAULT) {
            // Main.immediate may execute inline; yield before touching the lifecycle bookkeeping.
            kotlinx.coroutines.yield()
            try {
                val result = withTimeoutOrNull(PowerWidgetState.OPERATION_TIMEOUT_MS) {
                    (application as App).controller.control(
                        if (action == PowerWidgetState.ON) ControlAction.POWER_ON else ControlAction.POWER_OFF,
                        initial.vehicleId,
                    )
                }
                val success = result is ControlResult.Success
                val feedback = when {
                    result == null -> "操作超时，请确认车辆状态"
                    success -> if (action == PowerWidgetState.ON) "上次：上电指令成功" else "上次：下电指令成功"
                    else -> result.userMessage()
                }
                if (PowerWidgetProvider.exists(this@PowerWidgetControlService, id) &&
                    PowerWidgetProvider.valid(this@PowerWidgetControlService, store.read(id))) {
                    val completed = store.read(id).complete(token, success, feedback, System.currentTimeMillis())
                    store.write(id, completed)
                    PowerWidgetProvider.render(this@PowerWidgetControlService, id)
                    if (completed.resetAt > 0) {
                        startForeground(NOTIFICATION,
                            notification("下电成功，图标将在 1 分钟后恢复默认色"))
                        resets[id] = scope.launch {
                            delay((completed.resetAt - System.currentTimeMillis()).coerceAtLeast(0))
                            // Reconcile the current generation, never overwrite a newer tap.
                            PowerWidgetProvider.render(this@PowerWidgetControlService, id)
                            resets.remove(id)
                            stopIfIdle()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (PowerWidgetProvider.exists(this@PowerWidgetControlService, id)) {
                    store.write(id, store.read(id).complete(token, false, "控车失败，请重试", System.currentTimeMillis()))
                    PowerWidgetProvider.render(this@PowerWidgetControlService, id)
                }
            } finally {
                operation = null
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }
    private fun readinessProblem(state: PowerWidgetState): String? {
        val vehicle = (application as App).vehicleRepository.find(state.vehicleId)
            ?: return "请打开 TL-key 重新选择车辆"
        if (!vehicle.hasBleCredentials) return "请在 TL-key 中完成车辆配对"
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (vehicle.bleAddress == null) add(Manifest.permission.BLUETOOTH_SCAN)
            } else if (vehicle.bleAddress == null) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (required.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
            return "请在应用设置中授权蓝牙后重试"
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: return "此设备不支持蓝牙"
        return try {
            if (adapter.isEnabled) null else "请开启蓝牙后重新点击"
        } catch (_: SecurityException) {
            "请在应用设置中授权蓝牙后重试"
        }
    }

    private fun stopIfIdle() {
        if (operation == null && resets.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun notification(message: String): Notification {
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_power)
            .setContentTitle("TL-key 桌面控车").setContentText(message)
            .setContentIntent(content).setOngoing(true).build()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "widget_control"
        private const val NOTIFICATION = 401
    }
}
