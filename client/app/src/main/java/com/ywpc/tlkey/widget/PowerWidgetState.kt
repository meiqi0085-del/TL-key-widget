package com.ywpc.tlkey.widget

/** Persisted operation feedback, explicitly not real-time vehicle status. */
data class PowerWidgetState(
    val vehicleId: String = "",
    val ownerUid: String = "",
    val selected: String = "",
    val generation: String = "",
    val pendingUntil: Long = 0,
    val resetAt: Long = 0,
    val message: String = "点击按钮控车",
    val updatedAt: Long = 0,
) {
    fun begin(action: String, token: String, now: Long) = copy(
        selected = action, generation = token, pendingUntil = now + OPERATION_TIMEOUT_MS,
        resetAt = 0, message = if (action == ON) "正在上电…" else "正在下电…", updatedAt = now,
    )

    fun complete(token: String, success: Boolean, feedback: String, now: Long): PowerWidgetState {
        if (token != generation || pendingUntil == 0L) return this
        return copy(
            selected = if (success) selected else "", pendingUntil = 0,
            resetAt = if (success && selected == OFF) now + RESET_DELAY_MS else 0,
            message = feedback, updatedAt = now,
        )
    }

    fun reconcile(now: Long): PowerWidgetState = when {
        pendingUntil != 0L && now >= pendingUntil -> copy(
            selected = "", pendingUntil = 0, resetAt = 0,
            message = "操作中断或超时，请确认车辆状态", updatedAt = now,
        )
        resetAt != 0L && now >= resetAt -> copy(selected = "", resetAt = 0)
        else -> this
    }

    companion object {
        const val ON = "power_on"
        const val OFF = "power_off"
        const val RESET_DELAY_MS = 60_000L
        const val OPERATION_TIMEOUT_MS = 15_000L
    }
}
