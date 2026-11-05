package com.ywpc.tlkey.widget

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.ywpc.tlkey.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PowerWidgetAndroidTest {
    @Test fun remoteViewsInflateAndAcceptAllRuntimeActions() {
        val context = RuntimeEnvironment.getApplication()
        val views = RemoteViews(context.packageName, R.layout.widget_power)
        views.setInt(R.id.widget_on_icon, "setColorFilter", 0xff1976f3.toInt())
        views.setInt(R.id.widget_off_icon, "setColorFilter", 0xff414752.toInt())
        views.setBoolean(R.id.widget_on, "setEnabled", false)
        views.setBoolean(R.id.widget_off, "setEnabled", false)
        views.setTextViewText(R.id.widget_status, "正在上电…")
        val view = views.apply(context, FrameLayout(context))
        assertFalse(view.findViewById<android.view.View>(R.id.widget_on).isEnabled)
        assertNotNull(view.findViewById<ImageView>(R.id.widget_on_icon).colorFilter)
        assertEquals("正在上电…", view.findViewById<TextView>(R.id.widget_status).text.toString())
    }
    @Test fun restartRetainsVehicleAndAbsoluteResetDeadline() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("power_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        val completed = PowerWidgetState(vehicleId = "A", ownerUid = "owner")
            .begin(PowerWidgetState.OFF, "op", 100).complete("op", true, "ok", 2100)
        PowerWidgetStore(context).write(7, completed)
        val restored = PowerWidgetStore(context).read(7)
        assertEquals(completed, restored)
        assertEquals(PowerWidgetState.OFF, restored.reconcile(62099).selected)
        assertEquals("", restored.reconcile(62100).selected)
        PowerWidgetStore(context).remove(7)
        assertEquals("", PowerWidgetStore(context).read(7).vehicleId)
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
    fun renderReviewableWidgetStates() {
        val context = RuntimeEnvironment.getApplication()
        for (selected in listOf("", PowerWidgetState.ON, PowerWidgetState.OFF)) {
            val views = RemoteViews(context.packageName, R.layout.widget_power)
            views.setTextViewText(R.id.widget_vehicle, "我的台铃 · 0001")
            val defaultColor = context.getColor(R.color.widget_icon_default)
            val blue = context.getColor(R.color.widget_blue)
            views.setInt(R.id.widget_on_icon, "setColorFilter", if (selected == PowerWidgetState.ON) blue else defaultColor)
            views.setInt(R.id.widget_off_icon, "setColorFilter", if (selected == PowerWidgetState.OFF) blue else defaultColor)
            views.setTextViewText(R.id.widget_status, when (selected) {
                PowerWidgetState.ON -> "上次：上电指令成功"
                PowerWidgetState.OFF -> "上次：下电指令成功"
                else -> "点击按钮控车"
            })
            val view = views.apply(context, FrameLayout(context))
            val width = 360; val height = 400
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/reports/widget-preview-${selected.ifBlank { "default" }}.png")
            file.parentFile.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(view.findViewById<android.view.View>(R.id.widget_on).height >= 96)
            assertTrue(view.findViewById<android.view.View>(R.id.widget_time).bottom <= height)
        }
    }
    @Test @Config(qualifiers = "night") fun darkWidgetCanBeInflated() {
        val context = RuntimeEnvironment.getApplication()
        val view = RemoteViews(context.packageName, R.layout.widget_power).apply(context, FrameLayout(context))
        assertNotNull(view.findViewById<ImageView>(R.id.widget_on_icon))
    }
}
