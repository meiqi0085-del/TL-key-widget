package com.ywpc.tlkey.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.App
import com.ywpc.tlkey.ui.MainActivity

/** User-initiated, translucent bridge; never redirects through the full home screen on success. */
class PowerWidgetActionActivity : AppCompatActivity() {
    private var handled = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Toast.makeText(this, "设置完成后，请回到桌面重新点击", Toast.LENGTH_SHORT).show()
        finish()
    }
    private val bluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean("handled") ?: false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("handled", handled)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val action = intent.getStringExtra("power_action")
        if (!PowerWidgetProvider.exists(this, id) || action !in listOf(PowerWidgetState.ON, PowerWidgetState.OFF)) {
            finish(); return
        }
        val store = PowerWidgetStore(this)
        val state = store.read(id).reconcile(System.currentTimeMillis())
        if (state.pendingUntil > 0) { finish(); return }
        if (!PowerWidgetProvider.valid(this, state)) {
            startActivity(Intent(this, PowerWidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish(); return
        }
        val app = application as App
        val vehicle = app.vehicleRepository.find(state.vehicleId) ?: run { finish(); return }
        if (!vehicle.hasBleCredentials) {
            fail(id, "请在 TL-key 中选择该车辆并完成配对")
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (vehicle.bleAddress == null) add(Manifest.permission.BLUETOOTH_SCAN)
            } else if (vehicle.bleAddress == null) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            fail(id, "请授权蓝牙后重新点击")
            permissions.launch(missing.toTypedArray()); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { fail(id, "此设备不支持蓝牙"); finish(); return }
        if (!adapter.isEnabled) {
            fail(id, "请开启蓝牙后重新点击")
            bluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, PowerWidgetControlService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("power_action", action))
        } catch (e: RuntimeException) {
            fail(id, "后台控车未能启动，请打开 TL-key 后重试")
        }
        finish()
    }
    private fun fail(id: Int, message: String) {
        val store = PowerWidgetStore(this)
        store.write(id, store.read(id).copy(selected = "", resetAt = 0, pendingUntil = 0,
            message = message, updatedAt = System.currentTimeMillis()))
        PowerWidgetProvider.render(this, id)
    }
}
