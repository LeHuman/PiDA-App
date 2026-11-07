package com.pida.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.pida.app.data.PiApiClient
import com.pida.app.data.PreferencesManager
import com.pida.app.data.StateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget receiver. There is exactly one generic provider; every
 * pinned instance carries its schema binding in the pin-time widget
 * options extras:
 *
 *   pida_kind  = "toggle" | "button"
 *   pida_group = group path (toggle)
 *   pida_param = bool param name, resolved concrete at pin time (toggle)
 *   pida_action= full legacy action path (button)
 *
 * A single receiver renders per-id content (any instance count).
 *
 * Rendering is cache-only ([PiWidgets.render] reads StateCache, zero
 * network), so onUpdate always answers instantly and works offline. Taps
 * dispatch on the process-wide IO scope below so they survive receiver
 * teardown (a device settle can take seconds — don't rely on the
 * broadcast window).
 *
 * Stale pins (bound group/param/action no longer in the schema from cache)
 * render an "unavailable — re-add" placeholder instead of crashing.
 * Nothing here may throw.
 */
class PiAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) PiWidgets.render(context, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Some launchers merge the pin-time extras into the options after
        // the first update; render again whenever options appear.
        PiWidgets.render(context, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PIN_SUCCESS -> try {
                pinConfirmed(context, intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "pin confirmation failed: ${e.message}")
            }
            ACTION_TOGGLE_TAP -> try {
                toggleTap(context, idOf(intent))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "toggle tap handling failed: ${e.message}")
            }
            ACTION_BUTTON_TAP -> try {
                buttonTap(context, idOf(intent))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "button tap handling failed: ${e.message}")
            }
        }
    }

    private fun idOf(intent: Intent): Int =
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    /**
     * The launcher accepted the pin and filled [AppWidgetManager.EXTRA_APPWIDGET_ID]
     * into the success PendingIntent's fill-in intent. Persist the binding that
     * MainActivity resolved at pin time (carried in this broadcast's extras), then
     * render. Kept independent of getAppWidgetOptions, which many launchers never
     * populate with pin-time extras — the options read in [PiWidgets.readBinding]
     * remains as a defensive fallback for launchers that DO merge them.
     */
    private fun pinConfirmed(context: Context, intent: Intent) {
        val id = idOf(intent)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            android.util.Log.w(TAG, "pin success without an appWidgetId — dropped")
            return
        }
        val carried = intent.extras
        val binding = carried?.keySet().orEmpty()
            .filter { it.startsWith("pida_") }
            .mapNotNull { key -> carried?.getString(key)?.let { key to it } }
            .toMap()
        if (binding[EXTRA_KIND] == null || binding[EXTRA_GROUP] == null) {
            android.util.Log.w(TAG, "pin success for $id without a binding — dropped")
            return
        }
        StateCache.getInstance(context).putWidgetBinding(id, binding)
        android.util.Log.i(TAG, "pinned widget $id -> ${binding[EXTRA_KIND]} ${binding[EXTRA_GROUP]}")
        PiWidgets.render(context, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            StateCache.getInstance(context).deleteWidgetBinding(id)
        }
    }

    // ---- dispatches ----

    /**
     * Mirror of the settled-truth pipeline used by the tiles: flip the
     * cached bool and re-render immediately (fast feedback), dispatch the
     * partial delta on IO, then write StateCache with the server's settled
     * state and push the widget again. Failure reverts to the pre-tap state.
     */
    private fun toggleTap(context: Context, appWidgetId: Int) {
        val binding = PiWidgets.readBinding(context, appWidgetId) ?: return
        val param = binding.param ?: return
        val cache = StateCache.getInstance(context)
        val previous = cache.getGroupState(binding.group)
        val wasOn = (previous?.get(param) as? Boolean) == true

        val assumed = (previous ?: emptyMap<String, Any?>()).toMutableMap()
        assumed[param] = !wasOn
        cache.putGroupState(binding.group, assumed)
        PiWidgets.render(context, appWidgetId)
        android.util.Log.i(TAG, "toggle tap ${binding.group}/$param -> ${!wasOn}")

        dispatchScope.launch {
            val setPath = PiWidgets.catalog(context)
                .firstOrNull { it.path == binding.group }
                ?.setAction?.path
            if (setPath == null) {
                android.util.Log.w(TAG, "toggle binding stale: ${binding.group}")
                return@launch
            }
            val api = PiApiClient.getInstance(PreferencesManager.getInstance(context))
            api.dispatch(setPath, mapOf(param to !wasOn))
                .onSuccess { state ->
                    android.util.Log.i(TAG, "toggle settled ${binding.group}")
                    cache.putGroupState(binding.group, state)
                    PiWidgets.render(context, appWidgetId)
                    // Latent all-group settle check (fan-out actions change
                    // other groups; some devices reflect late).
                    PiStateSync.scheduleAfterAction(context)
                }
                .onFailure { t ->
                    android.util.Log.w(TAG, "toggle failed: ${PiApiClient.friendlyError(t)}")
                    previous?.let { cache.putGroupState(binding.group, it) }
                    // Red badge: the last dispatch failed; stays until the
                    // next render push (state refresh, app open, next tap).
                    PiWidgets.render(context, appWidgetId, error = true)
                    // Failures are often transient (lane superseded, momentary
                    // load): the settle passes re-read truth as soon as the
                    // Pi answers a state read again, clearing the red badge.
                    PiStateSync.scheduleAfterAction(context)
                }
        }
    }

    /** Legacy no-arg action: fire and log, no state verification — the same
     * semantics the tiles render legacy buttons with. */
    private fun buttonTap(context: Context, appWidgetId: Int) {
        val binding = PiWidgets.readBinding(context, appWidgetId) ?: return
        val actionPath = binding.action ?: return
        android.util.Log.i(TAG, "button tap $actionPath")
        dispatchScope.launch {
            val api = PiApiClient.getInstance(PreferencesManager.getInstance(context))
            api.dispatchLegacy(actionPath)
                .onSuccess {
                    android.util.Log.i(TAG, "button dispatched $actionPath")
                    // Buttons (esp. group resets) change groups beyond their own.
                    PiStateSync.scheduleAfterAction(context)
                }
                .onFailure { t ->
                    android.util.Log.w(TAG, "button failed: ${PiApiClient.friendlyError(t)}")
                    // Red badge on the button tile until the next push.
                    PiWidgets.render(context, appWidgetId, error = true)
                    // Transient failures (lane superseded, load): settle
                    // passes re-read truth and clear the red badge.
                    PiStateSync.scheduleAfterAction(context)
                }
        }
    }

    companion object {
        private const val TAG = "PiDA-W"

        const val EXTRA_KIND = "pida_kind"
        const val EXTRA_GROUP = "pida_group"
        const val EXTRA_PARAM = "pida_param"
        const val EXTRA_ACTION = "pida_action"
        const val KIND_TOGGLE = "toggle"
        const val KIND_BUTTON = "button"
        const val ACTION_TOGGLE_TAP = "com.pida.app.widget.TOGGLE"
        const val ACTION_BUTTON_TAP = "com.pida.app.widget.BUTTON"
        const val ACTION_PIN_SUCCESS = "com.pida.app.widget.PIN"

        /**
         * Unique-per-request nonce in the success PendingIntent: two "Add"
         * taps for the same widget kind in a row must not collapse into one
         * PendingIntent (identical intent signatures get merged, so the
         * second launch would overwrite the first — and the launcher fills
         * the appWidgetId into whichever survives).
         */
        const val EXTRA_PIN_NONCE = "pida_nonce"

        /**
         * Process-level scope for dispatches: outlives receiver teardown,
         * never blocks the main thread.
         */
        val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Pin-time extras bundle for a widget instance. */
        fun bindingExtras(kind: String, group: String?, param: String?, action: String?): Bundle =
            Bundle().apply {
                putString(EXTRA_KIND, kind)
                group?.let { putString(EXTRA_GROUP, it) }
                param?.let { putString(EXTRA_PARAM, it) }
                action?.let { putString(EXTRA_ACTION, it) }
            }
    }
}
