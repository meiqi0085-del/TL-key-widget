package com.ywpc.tlkey.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.ui.LoginActivity
import com.ywpc.tlkey.ui.MainActivity
import com.ywpc.tlkey.ui.setupEdgeToEdge

class PowerWidgetConfigureActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }
    override fun onResume() {
        super.onResume()
        showConfiguration()
    }
    private fun showConfiguration() {
        val app = application as App
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        setContentView(root)
        setupEdgeToEdge(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 24); root.addView(this)
        }
        fun button(value: String, action: () -> Unit) = MaterialButton(this).apply {
            text = value; setOnClickListener { action() }; root.addView(this)
        }
        text("TL-key 桌面控车", 26f)
        text("一张卡片，固定控制一辆车。\n点击上电或下电，图标变蓝；下电成功 1 分钟后恢复默认色。\n\n蓝色是操作反馈，不代表实时车况。下电请在停车后操作。", 16f)
        if (!app.loginManager.hasSession()) {
            text("先登录台铃账号，再回来选择车辆。", 16f)
            button("登录 TL-key") { startActivity(Intent(this, LoginActivity::class.java)) }
            return
        }
        val vehicles = app.loginManager.cachedVehicles()
        if (vehicles.isEmpty()) {
            text("尚未添加车辆，请先在 TL-key 中完成添加和配对。", 16f)
            button("打开 TL-key") { startActivity(Intent(this, MainActivity::class.java)) }
            return
        }
        val oldId = PowerWidgetStore(this).read(widgetId).vehicleId
        var selected = vehicles.indexOfFirst { it.id == oldId }.takeIf { it >= 0 } ?: 0
        val labels = vehicles.map { "${it.name.ifBlank { "我的台铃" }} · ${it.id.takeLast(4)}" }.toTypedArray()
        val selection = button("车辆：${labels[selected]} ▾") { }
        selection.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("选择小组件控制的车辆")
                .setSingleChoiceItems(labels, selected) { dialog, index ->
                    selected = index; selection.text = "车辆：${labels[index]} ▾"; dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        button(if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) "添加到桌面" else "保存小组件") {
            val vehicle = vehicles[selected]
            val uid = app.authRepository.currentUid().orEmpty()
            if (uid.isBlank() || !vehicle.hasBleCredentials) {
                Toast.makeText(this, "请先在首页选择这辆车，完成配对后再添加", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java)); return@button
            }
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                if (!PowerWidgetProvider.exists(this, widgetId)) { finish(); return@button }
                PowerWidgetStore(this).write(widgetId, PowerWidgetState(vehicleId = vehicle.id, ownerUid = uid))
                PowerWidgetProvider.render(this, widgetId)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            } else {
                val manager = AppWidgetManager.getInstance(this)
                if (!manager.isRequestPinAppWidgetSupported) {
                    MaterialAlertDialogBuilder(this).setTitle("从桌面添加")
                        .setMessage("长按桌面空白处 → 小组件 / 小工具 → TL-key 桌面控车，拖到桌面后选择车辆。")
                        .setPositiveButton("知道了", null).show()
                    return@button
                }
                // The launcher supplies the allocated widget ID as a fill-in extra.
                val callback = PendingIntent.getBroadcast(this, 0,
                    Intent(this, PowerWidgetPinnedReceiver::class.java).apply {
                        data = Uri.parse("tlkey-pin://${java.util.UUID.randomUUID()}")
                        putExtra("vehicle", vehicle.id); putExtra("owner", uid)
                    }, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
                manager.requestPinAppWidget(ComponentName(this, PowerWidgetProvider::class.java), null, callback)
            }
        }
        button("返回") { finish() }
    }
}

class PowerWidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (!PowerWidgetProvider.exists(context, id)) return
        val state = PowerWidgetState(vehicleId = intent.getStringExtra("vehicle").orEmpty(),
            ownerUid = intent.getStringExtra("owner").orEmpty())
        if (!PowerWidgetProvider.valid(context, state)) return
        PowerWidgetStore(context).write(id, state)
        PowerWidgetProvider.render(context, id)
    }
}
