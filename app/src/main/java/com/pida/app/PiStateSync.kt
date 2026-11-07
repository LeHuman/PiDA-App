package com.pida.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Latent settle-sync after any successful action dispatch.
 *
 * Why: a dispatch response carries only the target group's settled state.
 * Fan-out actions (e.g. group/reset) change OTHER groups too, and some
 * devices reflect late — so after any action we re-read EVERY group's state
 * four times: ~0.5s, ~1s, ~3s and ~8s after the action. Each read is
 * written through StateCache (the widgets' render source) and re-renders
 * the pinned widgets.
 *
 * A new action while a cascade runs RESTARTS it: the schedule is measured
 * from the latest action (last action wins). Nothing is lost — every pass
 * re-reads all groups, and the cancelled schedule is instantly replaced by
 * a fresh one anchored to the new action.
 *
 * No server changes, no persistent service, no polling loop: the cascade
 * only exists for ~8s after an action actually happened.
 *
 * Safe to call from any thread; never throws.
 */
object PiStateSync {
    private const val TAG = "PiDA-Sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    /** Restart the +0.5/1/3/8s settle cascade, anchored to now. Call after
     * every successful dispatch (widget tap, Device Controls tile,
     * ControlTapActivity row, in-app row). */
    fun scheduleAfterAction(context: android.content.Context) {
        val app = context.applicationContext
        synchronized(lock) {
            job?.cancel()
            job = scope.launch {
                try {
                    // Four settle passes from the action: quick catches at
                    // 0.5s/1s, then late reflectors at 3s and 8s (fan-out
                    // lanes settle at different speeds).
                    delay(500)
                    refreshAllGroups(app)
                    delay(500)
                    refreshAllGroups(app)
                    delay(2000)
                    refreshAllGroups(app)
                    delay(5000)
                    refreshAllGroups(app)
                } catch (e: CancellationException) {
                    // Superseded by a newer action — replaced, not failed.
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "settle sync failed: ${e.message}")
                }
            }
        }
    }

    /** Read every group's state in parallel; each success writes the cache
     * and re-renders widgets (same pattern as the Device Controls service).
     * Launched on the shared scope on purpose: cancelling the cascade's
     * schedule must NOT kill in-flight state fetches. */
    private fun refreshAllGroups(context: android.content.Context) {
        val groups = PiWidgets.catalog(context)
        if (groups.isEmpty()) return
        val api = com.pida.app.data.PiApiClient.getInstance(
            com.pida.app.data.PreferencesManager.getInstance(context))
        val cache = com.pida.app.data.StateCache.getInstance(context)
        var checked = 0
        for (group in groups) {
            val spec = group.setAction ?: continue
            if (spec.outputs.isEmpty()) continue
            checked++
            scope.launch {
                api.getState(spec.path).onSuccess { state ->
                    cache.putGroupState(group.path, state)
                    PiWidgets.refreshAll(context)
                }
            }
        }
        if (checked > 0) android.util.Log.i(TAG, "settle pass: $checked group(s)")
    }
}
