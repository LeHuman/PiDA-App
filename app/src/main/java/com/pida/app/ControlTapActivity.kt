package com.pida.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.R.attr as MAttr
import com.google.android.material.R.style as MStyle
import com.pida.app.data.ControlCatalog
import com.pida.app.data.DeviceGroup
import com.pida.app.data.LegacyActionSpec
import com.pida.app.data.ParamSpec
import com.pida.app.data.PiApiClient
import com.pida.app.data.PreferencesManager
import com.pida.app.data.StateCache
import com.pida.app.databinding.ActivityControlTapBinding
import com.pida.app.databinding.ItemLegacyActionButtonBinding
import com.pida.app.databinding.ItemParamSliderRowBinding
import com.pida.app.databinding.ItemParamSwitchRowBinding
import com.pida.app.ui.FlowRowLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Interactive control page for tile body taps.
 *
 * One UI's Device Controls panel opens this activity when a tile body is
 * tapped. The page renders the device GROUP's full interactive controls —
 * the same switch/slider/legacy-button content as MainActivity's group
 * card — and does NOT dispatch anything on open: opening only reads state
 * (cached seed first, then a fresh GET). The user interacts on the page.
 *
 * Id scheme (same as the panel's controls):
 *   ctl:<group>            that group's full control page
 *   ctl:<group>:<param>    same page (all of a group's tiles are equivalent)
 *   btn:<endpoint path>    the group whose legacy list contains that path
 * Unknown/stale ids show "Removed from pipeline" and self-dismiss after a
 * short beat.
 *
 * Dispatch semantics are copied from MainActivity (no refactor there):
 *   - switch rows dispatch a REAL BOOLEAN (value > 0.5); the server rejects
 *     floats for bool params
 *   - slider rows dispatch debounced (300 ms), int as toInt(), float as
 *     toDouble()
 *   - applying state programmatically sets row.switch.tag = "ui" so the
 *     listener's echo does not re-dispatch
 *   - dispatch success applies the readback via applyState and writes it to
 *     StateCache; failure reverts to the last known state
 *   - legacy buttons dispatchLegacy() on tap with an optimistic flip of the
 *     cached button state
 *   - after any successful group-state change the open panel is pushed
 *     PiControlsProviderService.instance?.ingestTapResult("ctl:" + group)
 */
class ControlTapActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var binding: ActivityControlTapBinding
    private lateinit var apiClient: PiApiClient

    private var group: DeviceGroup? = null
    private var lastKnownState: Map<String, Any?>? = null
    private val rows = mutableMapOf<String, RowUi>()
    private val updateJobs = mutableMapOf<String, Job>()

    private inner class RowUi(
        val slider: Slider?,
        val label: TextView,
        val switch: MaterialSwitch?,
        val spec: ParamSpec?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Match MainActivity's Material You palette (wallpaper-derived on
        // Samsung) instead of the fallback purple where dynamic color is
        // available. Must run before super so the theme wraps first.
        try {
            DynamicColorsHelper.apply(this)
        } catch (e: Exception) {
            android.util.Log.w("PiControls", "dynamic color unavailable: " + e)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityControlTapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = PiApiClient.getInstance(PreferencesManager.getInstance(this))

        binding.btnRefresh.visibility = View.GONE
        binding.btnRefresh.setOnClickListener { group?.let { loadFreshState(it) } }

        val controlId = intent.getStringExtra(EXTRA_CONTROL_ID)
        if (controlId == null) {
            showUnknown()
            return
        }
        android.util.Log.i("PiControls", "tile tap running: " + controlId)
        scope.launch {
            try {
                resolve(controlId)
            } catch (e: Exception) {
                android.util.Log.w("PiControls", "tile tap load failed: " + e)
                runUi { showStatus("Couldn't reach the device") }
            }
        }
    }

    /** Resolve the tapped id to a group, build the page, then load state. */
    private suspend fun resolve(controlId: String) {
        val cache = StateCache.getInstance(this)
        // Cold cache (fresh install / restored snapshot): fetch the schema
        // from the Pi instead of falling to "Removed from pipeline".
        if (cache.getSchemaJson() == null) {
            android.util.Log.i("PiControls", "tile tap: cold schema cache, fetching")
            apiClient.getSchema().onSuccess { fetched ->
                cache.putSchema(fetched)
            }
        }
        val schemaJson = cache.getSchemaJson() ?: run {
            android.util.Log.w("PiControls", "tile tap " + controlId + ": no cached schema")
            runUi { showUnknown() }
            return
        }
        val groups = ControlCatalog.fromSchema(cache.parseObject(schemaJson))
        android.util.Log.i("PiControls", "tile tap schema ok, groups: " + groups.size)

        val target: DeviceGroup? = when {
            controlId.startsWith("ctl:") -> resolveCtl(controlId.removePrefix("ctl:"), groups)
            controlId.startsWith("btn:") ->
                groups.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == controlId } }
            else -> null
        }
        if (target == null) {
            android.util.Log.w("PiControls", "tile tap " + controlId + ": stale id")
            runUi { showUnknown() }
            return
        }
        android.util.Log.i("PiControls", "tile tap group: " + target.path)

        runUi { buildGroupPage(target) }

        // Seed from the cache (instant paint), then fetch fresh state.
        cache.getGroupState(target.path)?.let { cached -> runUi { applyState(target, cached) } }
        loadFreshState(target)
    }

    /** ctl:<group> and ctl:<group>:<param> resolve to the same group. */
    private fun resolveCtl(rest: String, groups: List<DeviceGroup>): DeviceGroup? {
        groups.firstOrNull { it.path == rest }?.let { return it }
        val idx = rest.lastIndexOf(':')
        if (idx == -1) return null
        return groups.firstOrNull { it.path == rest.substring(0, idx) }
    }

    /**
     * Fresh state: GET-as-state readback of the group's set action. Skipped
     * for groups without a set action or with no outputs to read.
     */
    private fun loadFreshState(target: DeviceGroup) {
        val spec = target.setAction ?: return
        if (spec.outputs.isEmpty()) return
        runUi { binding.progress.visibility = View.VISIBLE }
        scope.launch {
            apiClient.getState(spec.path)
                .onSuccess { state ->
                    android.util.Log.i("PiControls", "tile tap state ok: " + target.path)
                    runUi {
                        binding.progress.visibility = View.GONE
                        applyState(target, state)
                    }
                }
                .onFailure {
                    android.util.Log.w("PiControls", "tile tap state failed: " + it.message)
                    runUi {
                        binding.progress.visibility = View.GONE
                        if (lastKnownState == null) showStatus("Couldn't reach the device")
                    }
                }
        }
    }

    // ---- page build (ported from MainActivity.buildGroupCard) ----

    private fun buildGroupPage(target: DeviceGroup) {
        group = target
        rows.clear()
        binding.deviceTitle.text = target.title
        binding.btnRefresh.visibility =
            if (target.setAction != null) View.VISIBLE else View.GONE
        buildCardContent(target, binding.controlsContainer)
    }

    private fun buildCardContent(target: DeviceGroup, col: LinearLayout) {
        val spec = target.setAction

        spec?.let {
            for (param in it.params) {
                when {
                    param.name !in it.inputs -> Unit  // output-only param: not interactive
                    param.isBool -> {
                        val row = ItemParamSwitchRowBinding.inflate(layoutInflater, col, false)
                        row.titleParam.text = ControlCatalog.prettify(param.name)
                        row.switchParam.setOnCheckedChangeListener { _, checked ->
                            if (row.switchParam.tag == "ui") return@setOnCheckedChangeListener
                            dispatchParam(target, param, if (checked) 1.0 else 0.0)
                        }
                        col.addView(row.root, marginTop(4))
                        rows["param:${param.name}"] =
                            RowUi(null, row.textParamValue, row.switchParam, param)
                    }
                    param.isNumeric -> {
                        val row = ItemParamSliderRowBinding.inflate(layoutInflater, col, false)
                        row.titleParam.text = ControlCatalog.prettify(param.name)
                        val label = row.textParamValue
                        val slider = row.sliderParam
                        val min = (param.min ?: 0.0).toFloat()
                        val max = (param.max ?: 100.0).toFloat()
                        val step = chooseStep(min, max, param.type == "float")
                        slider.valueFrom = min
                        slider.valueTo = max
                        slider.stepSize = step
                        slider.value = min  // in-range before layout validation
                        slider.addOnChangeListener { _, value, fromUser ->
                            label.text = labelFor(param, value.toDouble())
                            if (!fromUser) return@addOnChangeListener
                            val key = "${target.path}:${param.name}"
                            updateJobs[key]?.cancel()
                            updateJobs[key] = scope.launch {
                                delay(300)  // debounce: latest-wins upstream too
                                dispatchParam(target, param, value.toDouble())
                            }
                        }
                        col.addView(row.root, marginTop(12))
                        rows["param:${param.name}"] = RowUi(slider, label, null, param)
                    }
                    else -> col.addView(
                        TextView(this).apply {
                            text = "${param.name} (${param.type}): not shown in this view"
                            setTextAppearance(MStyle.TextAppearance_Material3_BodySmall)
                            setTextColor(themeColor(MAttr.colorOutline))
                        },
                        marginTop(8))
                }
            }
        }

        if (target.legacy.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = "Actions"
                setTextAppearance(MStyle.TextAppearance_Material3_LabelMedium)
                setTextColor(themeColor(MAttr.colorOnSurfaceVariant))
            }, marginTop(16))
            val flow = FlowRowLayout(this)
            for (action in target.legacy) {
                val btn = ItemLegacyActionButtonBinding.inflate(layoutInflater, flow, false).root
                btn.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                }
                btn.text = action.title
                btn.setOnClickListener { dispatchLegacyAction(action) }
                flow.addView(btn)
            }
            col.addView(flow, marginTop(8))
        }

        if (spec == null && target.legacy.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "No interactive controls for this device."
                setTextAppearance(MStyle.TextAppearance_Material3_BodyMedium)
                setTextColor(themeColor(MAttr.colorOnSurfaceVariant))
            }, marginTop(8))
        }
    }

    // ---- dispatch (ported from MainActivity.dispatchParam / legacy) ----

    private fun dispatchParam(target: DeviceGroup, param: ParamSpec, value: Double) {
        val spec = target.setAction ?: return
        val body = mapOf<String, Any?>(
            param.name to when (param.type) {
                "int" -> value.toInt()
                "bool" -> value > 0.5  // REAL boolean: the server rejects floats
                else -> value
            })
        android.util.Log.i("PiControls", "tile tap dispatch " + spec.path + " body=" + body)
        scope.launch {
            apiClient.dispatch(spec.path, body)
                .onSuccess { state ->
                    android.util.Log.i(
                        "PiControls", "tile tap dispatch ok: " + target.path)
                    // Cache write + panel push are NOT view operations: they
                    // must survive the page being swiped away before the
                    // dispatch lands — runUi guards its block with
                    // isDestroyed and would silently drop both, leaving the
                    // tile stuck on its optimistic value. Same-thread
                    // ordering: the ingest reads the cache this just wrote.
                    StateCache.getInstance(applicationContext)
                        .putGroupState(target.path, state)
                    // Widgets render from the same cache; push the settled
                    // truth out to every pinned id (cheap, no network).
                    PiWidgets.refreshAll(applicationContext)
                    PiControlsProviderService.instance
                        ?.ingestTapResult("ctl:" + target.path)
                    // Latent all-group settle check (fan-out actions change
                    // other groups; some devices reflect late).
                    PiStateSync.scheduleAfterAction(applicationContext)
                    runUi {
                        applyState(target, state)
                        showStatus(null)
                    }
                }
                .onFailure {
                    android.util.Log.w(
                        "PiControls",
                        "tile tap dispatch " + spec.path + " failed: " + it.message)
                    runUi {
                        // Revert the row to the last known real state — the
                        // switch/slider the user moved may not match reality.
                        lastKnownState?.let { last -> applyState(target, last) }
                        showStatus(
                            ControlCatalog.prettify(param.name) + ": " +
                                PiApiClient.friendlyError(it))
                    }
                }
        }
    }

    private fun dispatchLegacyAction(action: LegacyActionSpec) {
        val cache = StateCache.getInstance(this)
        val prev = cache.getAllButtonStates()[action.path] ?: false
        android.util.Log.i("PiControls", "tile tap btn dispatch " + action.path)
        // Optimistic flip of the cached button state; failure reverts it.
        cache.putButtonState(action.path, !prev)
        scope.launch {
            apiClient.dispatchLegacy(action.path)
                .onSuccess {
                    android.util.Log.i("PiControls", "tile tap btn " + action.path + " ok")
                    PiControlsProviderService.instance
                        ?.ingestTapResult("btn:" + action.path)
                    // Buttons (esp. group resets) change groups beyond their own.
                    PiStateSync.scheduleAfterAction(applicationContext)
                    runUi { showStatus(action.title + ": sent") }
                }
                .onFailure {
                    android.util.Log.w(
                        "PiControls",
                        "tile tap btn " + action.path + " failed: " + it.message)
                    cache.putButtonState(action.path, prev)
                    runUi { showStatus(action.title + ": " + PiApiClient.friendlyError(it)) }
                }
        }
    }

    // ---- state application (ported from MainActivity.applyState) ----

    private fun applyState(target: DeviceGroup, state: Map<String, Any?>) {
        lastKnownState = state
        // Write-through: the Device Controls panel publishes from this cache.
        StateCache.getInstance(this).putGroupState(target.path, state)
        for ((key, row) in rows) {
            val name = key.removePrefix("param:")
            val spec = row.spec ?: continue
            val v = state[name] ?: continue
            if (row.switch != null) {
                row.switch.tag = "ui"  // suppress the listener's echo dispatch
                try { row.switch.isChecked = (v as? Boolean) == true } finally { row.switch.tag = null }
                row.label.text = if ((v as? Boolean) == true) "On" else "Off"
            } else if (v is Boolean) {
                row.label.text = labelFor(spec, if (v) 1.0 else 0.0)
            }
            if (row.slider != null && v is Number) {
                row.slider.value = (v as Number).toDouble().coerceIn(
                    row.slider.valueFrom.toDouble(), row.slider.valueTo.toDouble()).toFloat()
            }
            if (v is Number) row.label.text = labelFor(spec, v.toDouble())
            if (row.switch == null && v is Boolean) row.label.text = labelFor(spec, if (v) 1.0 else 0.0)
        }
    }

    /** Live value for a param row (the row title carries the param name). */
    private fun labelFor(param: ParamSpec, value: Double = Double.NaN): String {
        val unit = when {
            param.name.contains("temp", ignoreCase = true) -> " K"
            param.name.contains("bright", ignoreCase = true) -> " %"
            else -> ""
        }
        if (value.isNaN()) return "--"
        if (param.type == "bool") return if (value > 0.5) "On" else "Off"
        return "${value.toInt()}$unit"
    }

    private fun chooseStep(min: Float, max: Float, isFloat: Boolean): Float {
        val range = max - min
        if (range <= 0f) return 1f
        if (isFloat) return (range / 100f).coerceAtLeast(0.01f)
        for (c in floatArrayOf(1f, 2f, 4f, 5f, 10f, 20f, 25f, 50f)) {
            if (c >= range / 100f && abs(range % c) < 1e-6) return c
        }
        for (c in floatArrayOf(100f, 200f, 250f, 500f, 1000f)) {
            if (abs(range % c) < 1e-6) return c
        }
        return 1f
    }

    // ---- page states ----

    /** Unknown/stale id: acknowledge, linger briefly, self-dismiss. */
    private fun showUnknown() {
        android.util.Log.i("PiControls", "tile tap: unknown id, status page")
        binding.contentColumn.visibility = View.GONE
        binding.unknownContainer.visibility = View.VISIBLE
        mainHandler.postDelayed({
            if (!isDestroyed && !isFinishing) finishAndRemoveTask()
        }, UNKNOWN_LINGER_MS)
    }

    /** Transient status line; null hides it. */
    private fun showStatus(message: String?) {
        if (message == null) {
            binding.statusLine.visibility = View.GONE
        } else {
            binding.statusLine.text = message
            binding.statusLine.visibility = View.VISIBLE
        }
    }

    /** UI-thread render helper; the page may already be swiped away. */
    private fun runUi(block: () -> Unit) {
        mainHandler.post {
            if (!isDestroyed && !isFinishing) block()
        }
    }

    // ---- small view helpers ----

    /** Resolves a Material color role from the current theme (incl. dynamic colors). */
    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun marginTop(v: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(v) }

    private object DynamicColorsHelper {
        fun apply(activity: Activity) {
            com.google.android.material.color.DynamicColors
                .applyToActivityIfAvailable(activity)
        }
    }

    companion object {
        const val EXTRA_CONTROL_ID = "control_id"
        private const val UNKNOWN_LINGER_MS = 1200L
    }
}
