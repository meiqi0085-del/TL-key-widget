package com.ywpc.tlkey.widget

import android.content.Context
import org.json.JSONObject

class PowerWidgetStore(context: Context) {
    private val preferences = context.getSharedPreferences("power_widgets", Context.MODE_PRIVATE)

    fun read(id: Int): PowerWidgetState {
        val json = runCatching { JSONObject(preferences.getString(id.toString(), "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return PowerWidgetState(
            vehicleId = json.optString("vehicle"), ownerUid = json.optString("owner"),
            selected = json.optString("selected"), generation = json.optString("generation"),
            pendingUntil = json.optLong("pendingUntil"), resetAt = json.optLong("resetAt"),
            message = json.optString("message", "点击按钮控车"), updatedAt = json.optLong("updatedAt"),
        )
    }

    fun write(id: Int, state: PowerWidgetState) {
        val json = JSONObject().apply {
            put("vehicle", state.vehicleId); put("owner", state.ownerUid)
            put("selected", state.selected); put("generation", state.generation)
            put("pendingUntil", state.pendingUntil); put("resetAt", state.resetAt)
            put("message", state.message); put("updatedAt", state.updatedAt)
        }
        // Small state must survive process death before the control command starts.
        preferences.edit().putString(id.toString(), json.toString()).commit()
    }

    fun remove(id: Int) { preferences.edit().remove(id.toString()).commit() }
}
