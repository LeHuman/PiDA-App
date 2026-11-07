package com.pida.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pida.app.data.ControlCatalog
import com.pida.app.data.DeviceGroup
import com.pida.app.data.StateCache

/**
 * Cache-only rendering for home-screen widgets plus the static refresh hook
 * other surfaces call after state changes. Zero network here: the catalog
 * is parsed from StateCache.getSchemaJson(), per-group state from
 * StateCache.getGroupState(path). Safe to call from any thread; every entry
 * point is exception-guarded so stale pins can never crash a receiver.
 */
object PiWidgets {

    private const val TAG = "PiDA-W"

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    /**
     * Rebuild every instance of the generic widget provider from cache.
     * Cheap (no network) and called after any dispatch-induced cache push.
     */
    fun refreshAll(context: android.content.Context) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = android.content.ComponentName(
                context.packageName, PiAppWidgetProvider::class.java.name)
            for (id in manager.getAppWidgetIds(component)) {
                render(context, id)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "refreshAll failed: ${e.message}")
        }
    }

    /**
     * Re-render one widget id from cache. error=true marks a failed last
     * dispatch for that instance: the badge draws red until the next push.
     */
    fun render(context: android.content.Context, appWidgetId: Int, error: Boolean = false) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val views = viewsFor(context, appWidgetId, error) ?: return
            manager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "widget $appWidgetId render failed: ${e.message}")
        }
    }

    /**
     * The device catalog as last persisted from a successful /api/schema
     * fetch (raw JSON string — parsed with Gson, like the network path).
     */
    fun catalog(context: android.content.Context): List<DeviceGroup> {
        val raw = try {
            StateCache.getInstance(context).getSchemaJson()
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val json: Map<String, Any?> = gson.fromJson(raw, mapType) ?: emptyMap()
            ControlCatalog.fromSchema(json)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "schema cache unparsable: ${e.message}")
            emptyList()
        }
    }

    // ---- per-instance views ----

    private fun viewsFor(
        context: android.content.Context,
        appWidgetId: Int,
        error: Boolean
    ): RemoteViews? {
        val binding = readBinding(context, appWidgetId)
            ?: return textViews(context, "Unavailable\nRe-add")
        if (binding.kind != PiAppWidgetProvider.KIND_TOGGLE &&
            binding.kind != PiAppWidgetProvider.KIND_BUTTON)
            return textViews(context, "Unavailable\nRe-add")
        val groups = catalog(context)
        if (groups.isEmpty()) return textViews(context, "Offline")

        return when (binding.kind) {
            PiAppWidgetProvider.KIND_TOGGLE -> toggleViews(context, binding, appWidgetId, groups, loadIcon = true, error = error)
            else -> buttonViews(context, binding, appWidgetId, groups, loadIcon = true, error = error)
        }
    }

    /**
     * @param loadIcon: false when re-rendering after a settled-truth push —
     * the sync path had its chance to load the pixmap already; only the
     * id's current RemoteViews get bumped then (icon upgrades are pushed
     * asynchronously via another render()).
     */
    private fun toggleViews(
        context: android.content.Context,
        binding: WidgetBinding,
        appWidgetId: Int,
        groups: List<DeviceGroup>,
        loadIcon: Boolean,
        error: Boolean
    ): RemoteViews {
        val group = groups.firstOrNull { it.path == binding.group }
            ?: return staleViews(context)
        val toggle = group.setAction?.toggleParam
        if (toggle == null || toggle.name != binding.param) return staleViews(context)

        val state = StateCache.getInstance(context)
            .getGroupState(binding.group)?.get(toggle.name) as? Boolean
        val on = state == true

        val views = RemoteViews(context.packageName, R.layout.widget_toggle)
        // App-icon look: full-bleed icon, floating on the wallpaper. The badge
        // is always visible and encodes FOUR states: red = last dispatch
        // failed, green = on, hollow (dark outline) = off, amber = no cached
        // state yet (intermediate/unknown).
        views.setImageViewResource(
            R.id.widget_badge, when {
                error -> R.drawable.badge_error
                state == true -> R.drawable.badge_on
                state == false -> R.drawable.badge_off
                else -> R.drawable.badge_unknown
            })
        views.setViewVisibility(R.id.widget_badge, android.view.View.VISIBLE)
        views.setContentDescription(
            R.id.widget_icon, "${ControlCatalog.prettifyGroup(group.path)} toggle, ${if (on) "on" else "off"}")
        views.setOnClickPendingIntent(
            R.id.widget_root, tapPendingIntent(
                context, appWidgetId, PiAppWidgetProvider.ACTION_TOGGLE_TAP))
        if (loadIcon) setFirstIcon(context, views, appWidgetId, group.legacy.firstOrNull()?.path, group.setAction?.path, group.path)
        return views
    }

    private fun buttonViews(
        context: android.content.Context,
        binding: WidgetBinding,
        appWidgetId: Int,
        groups: List<DeviceGroup>,
        loadIcon: Boolean,
        error: Boolean
    ): RemoteViews {
        val action = groups.firstOrNull { it.path == binding.group }
            ?.legacy?.firstOrNull { it.path == binding.action }
            ?: return staleViews(context)

        val views = RemoteViews(context.packageName, R.layout.widget_button)
        views.setContentDescription(
            R.id.widget_icon,
            "${ControlCatalog.prettifyGroup(action.group)}: ${action.title}")
        // Buttons have no state of their own; the badge stays hidden unless
        // the last dispatch failed (red dot), where the render push carries
        // the flag.
        if (error) {
            views.setImageViewResource(R.id.widget_badge, R.drawable.badge_error)
            views.setViewVisibility(R.id.widget_badge, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_badge, android.view.View.GONE)
        }
        views.setOnClickPendingIntent(
            R.id.widget_root, tapPendingIntent(
                context, appWidgetId, PiAppWidgetProvider.ACTION_BUTTON_TAP))
        if (loadIcon) setIcon(context, views, action.path, appWidgetId)
        return views
    }

    /**
     * Icon ladder for surfaces that don't have their own action icon: try
     * each candidate (e.g. first legacy action, the set path, the group
     * path) in order; the first one that yields a bitmap wins. Generic —
     * candidates are schema paths only, no device identity involved.
     */
    private fun setFirstIcon(
        context: android.content.Context,
        views: RemoteViews,
        appWidgetId: Int,
        vararg candidates: String?
    ) {
        val cachedBitmap: Bitmap? = candidates
            .filterNotNull()
            .mapNotNull { PiWidgetIcons.peekCached(context, it) }
            .firstOrNull()
        if (cachedBitmap != null) {
            views.setImageViewBitmap(R.id.widget_icon, cachedBitmap)
            return
        }
        views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_power)
        PiWidgetIcons.loadFirst(context, candidates) { bitmap ->
            // Only re-render on success — a full-ladder failure (all 404)
            // settles on the generic icon; re-rendering on failure would
            // loop forever (render → setIcon → load → 404 → callback …).
            if (bitmap != null) render(context, appWidgetId)
        }
    }
    /** Stale pin placeholder: binding no longer present in the schema. */
    private fun staleViews(context: android.content.Context): RemoteViews =
        textViews(context, "Unavailable\nRe-add")

    private fun textViews(context: android.content.Context, message: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_unavailable)
        views.setTextViewText(R.id.widget_message, message)
        return views
    }

    // ---- icons ----

    /**
     * Real icon for the bound action, falling back to the generic bundled
     * icon on 404/failure. The pixmap is fetched/downscaled on a background
     * scope and pushed via re-render so the main thread never does IO.
     */
    private fun setIcon(
        context: android.content.Context,
        views: RemoteViews,
        iconSourcePath: String?,
        appWidgetId: Int
    ) {
        val cached = PiWidgetIcons.peekCached(context, iconSourcePath)
        if (cached != null) {
            views.setImageViewBitmap(R.id.widget_icon, cached)
            return
        }
        views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_power)
        if (iconSourcePath == null) return
        PiWidgetIcons.load(context, iconSourcePath) { bitmap ->
            // Only re-render when a bitmap actually arrived. A failed fetch
            // (404 etc.) settles on the generic icon — re-rendering on the
            // failure callback would loop forever (render → setIcon →
            // load → 404 → callback → render …).
            if (bitmap != null) {
                render(context, appWidgetId)
            }
        }
    }

    private fun tapPendingIntent(
        context: android.content.Context,
        appWidgetId: Int,
        action: String
    ): android.app.PendingIntent {
        val intent = Intent(context, PiAppWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = android.net.Uri.parse("widget://$appWidgetId/${action}")
        }
        return android.app.PendingIntent.getBroadcast(
            context, (action + appWidgetId).hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    // ---- pin-time binding ----

    data class WidgetBinding(
        val kind: String,
        val group: String,
        val param: String?,
        val action: String?
    )

    /**
     * Resolve a pinned widget's binding: the persisted pin-success record
     * first (written by [PiAppWidgetProvider] when the launcher delivers
     * the success broadcast with the system-filled appWidgetId), then the
     * widget options, defensively — some launchers DO merge pin-time
     * extras into the options (top level or nested under "extras"), some
     * never do.
     */
    fun readBinding(context: android.content.Context, appWidgetId: Int): WidgetBinding? {
        try {
            val persisted = StateCache.getInstance(context).getWidgetBinding(appWidgetId)
            if (persisted != null) {
                val kind = persisted[PiAppWidgetProvider.EXTRA_KIND]
                val group = persisted[PiAppWidgetProvider.EXTRA_GROUP]
                if (kind != null && group != null) return WidgetBinding(
                    kind = kind,
                    group = group,
                    param = persisted[PiAppWidgetProvider.EXTRA_PARAM],
                    action = persisted[PiAppWidgetProvider.EXTRA_ACTION])
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "binding cache read failed: ${e.message}")
        }
        return try {
            val options = AppWidgetManager.getInstance(context)
                ?.getAppWidgetOptions(appWidgetId) ?: return null
            fun text(key: String): String? {
                val top = options.getString(key)
                if (!top.isNullOrBlank()) return top
                val nested = options.getBundle("extras") ?: return null
                return nested.getString(key)
            }
            val kind = text(PiAppWidgetProvider.EXTRA_KIND) ?: return null
            val group = text(PiAppWidgetProvider.EXTRA_GROUP) ?: return null
            WidgetBinding(
                kind = kind,
                group = group,
                param = text(PiAppWidgetProvider.EXTRA_PARAM),
                action = text(PiAppWidgetProvider.EXTRA_ACTION))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "binding read failed: ${e.message}")
            null
        }
    }
}
