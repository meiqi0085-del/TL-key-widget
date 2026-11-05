package com.ywpc.tlkey.widget

import org.junit.Assert.*
import org.junit.Test

class PowerWidgetStateTest {
    private val start = PowerWidgetState(vehicleId = "car-a", ownerUid = "owner")
    @Test fun tapHighlightsImmediatelyAndPreservesBinding() {
        val state = start.begin(PowerWidgetState.ON, "a", 100)
        assertEquals(PowerWidgetState.ON, state.selected)
        assertEquals("car-a", state.vehicleId)
        assertEquals(15100L, state.pendingUntil)
        assertEquals(0L, state.resetAt)
    }
    @Test fun offTimerStartsAtSuccessNotAtTapAndExpiresAtSixtySeconds() {
        val done = start.begin(PowerWidgetState.OFF, "a", 100).complete("a", true, "ok", 3100)
        assertEquals(63100L, done.resetAt)
        assertEquals(PowerWidgetState.OFF, done.reconcile(63099).selected)
        assertEquals("", done.reconcile(63100).selected)
        assertEquals("ok", done.reconcile(63100).message)
    }
    @Test fun successfulOnDoesNotExpire() {
        val done = start.begin(PowerWidgetState.ON, "a", 0).complete("a", true, "ok", 100)
        assertEquals(PowerWidgetState.ON, done.reconcile(Long.MAX_VALUE).selected)
    }
    @Test fun newOnCancelsOffTimerAndOldCallbackCannotOverwriteNewTap() {
        val off = start.begin(PowerWidgetState.OFF, "off", 0).complete("off", true, "ok", 100)
        val on = off.begin(PowerWidgetState.ON, "on", 1000)
        assertEquals(0L, on.resetAt)
        assertEquals(on, on.complete("off", true, "stale", 1100))
        val done = on.complete("on", true, "on", 1200)
        assertEquals(PowerWidgetState.ON, done.reconcile(60100).selected)
    }
    @Test fun failuresClearBothHighlights() {
        for (action in listOf(PowerWidgetState.ON, PowerWidgetState.OFF)) {
            val failed = start.begin(action, "a", 100).complete("a", false, "failed", 1000)
            assertEquals("", failed.selected)
            assertEquals(0L, failed.resetAt)
            assertEquals(0L, failed.pendingUntil)
        }
    }
    @Test fun interruptedProcessReconcilesAndCannotAcceptLateResult() {
        val timedOut = start.begin(PowerWidgetState.OFF, "a", 100).reconcile(15100)
        assertEquals("", timedOut.selected)
        assertEquals(timedOut, timedOut.complete("a", true, "late", 16000))
    }
    @Test fun repeatedCompletionDoesNotExtendResetDeadline() {
        val done = start.begin(PowerWidgetState.OFF, "a", 0).complete("a", true, "ok", 100)
        assertEquals(done, done.complete("a", true, "ok", 500))
    }
    @Test fun separateWidgetsHaveIndependentState() {
        val other = PowerWidgetState(vehicleId = "car-b", ownerUid = "owner")
        val a = start.begin(PowerWidgetState.ON, "a", 0)
        assertEquals("car-a", a.vehicleId)
        assertEquals("", other.selected)
    }
}
