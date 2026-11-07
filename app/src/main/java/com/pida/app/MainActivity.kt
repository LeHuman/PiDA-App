package com.pida.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.R.attr as MAttr
import com.google.android.material.R.style as MStyle
import com.pida.app.data.ControlCatalog
import com.pida.app.data.DeviceGroup
import com.pida.app.data.ParamSpec
import com.pida.app.data.PiApiClient
import com.pida.app.data.PreferencesManager
import com.pida.app.data.StateCache
import com.pida.app.databinding.ActivityMainBinding
import com.pida.app.databinding.ItemLegacyActionButtonBinding
import com.pida.app.databinding.ItemParamSliderRowBinding
import com.pida.app.databinding.ItemParamSwitchRowBinding
import com.pida.app.databinding.ItemWidgetRowBinding
import com.pida.app.ui.FlowRowLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var apiClient: PiApiClient

    private var deviceGroups: List<DeviceGroup> = emptyList()
    private val groupStates = mutableMapOf<String, Map<String, Any?>>()
    private var schemaLoadFailed = false
    private val uiHolders = mutableMapOf<String, GroupCardUi>()
    private val updateJobs = mutableMapOf<String, Job>()

    /** One addable widget kind: shown in the "Home-screen widgets" list,
     * pin request carries this exact bundle as pin-time binding. */
    private data class WidgetEntry(
        val title: String,
        val subtitle: String,
        val iconPath: String?,
        val iconCandidates: Array<out String?>,
        val extras: android.os.Bundle
    )

    private inner class GroupCardUi(
        val container: LinearLayout,
        val rows: MutableMap<String, RowUi>
    )

    private inner class RowUi(
        val slider: Slider?,
        val label: TextView,
        val switch: MaterialSwitch?,
        val spec: ParamSpec?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Material You: on Android 12+ the activity theme receives the
        // wallpaper-derived dynamic color overlay on supported devices;
        // older devices keep the purple seed declared in themes.xml.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager.getInstance(this)
        apiClient = PiApiClient.getInstance(prefs)

        setupServerSettings()
        setupDeviceControlsButton()
        binding.btnRefreshDevices.setOnClickListener { refreshCatalog() }

        if (prefs.serverBaseUrl.isBlank()) {
            schemaLoadFailed = true
            renderCatalog()
        } else {
            refreshCatalog()
        }
    }

    private fun setupServerSettings() {
        binding.editServerUrl.setText(prefs.serverBaseUrl)

        binding.btnSaveServerUrl.setOnClickListener {
            val url = binding.editServerUrl.text?.toString().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.serverBaseUrl = url
            Toast.makeText(this, "Saved: ${prefs.serverBaseUrl}", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestConnection.setOnClickListener { testConnection() }
        setupBatteryFix()
    }

    /** One UI freezes the provider process in the background (Samsung
     * "Freecess"); the Device Controls panel then can't reach it and every
     * tile sits on "Loading" until the app is reopened. Exempting the app
     * from battery optimization stops the freezing. */
    private fun setupBatteryFix() {
        val powerManager = getSystemService(PowerManager::class.java)
        binding.batteryCard.visibility =
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) View.GONE
            else View.VISIBLE
        binding.btnBatteryFix.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // One UI variant without the direct dialog: send the user to
                // the app's battery settings page instead.
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
        }
    }

    private fun testConnection() {
        binding.textServerStatus.text = "Checking connection..."
        binding.textServerStatus.setTextColor(themeColor(MAttr.colorOnSurfaceVariant))

        lifecycleScope.launch {
            apiClient.getHealth().onSuccess { health ->
                binding.textServerStatus.text =
                    "Connected: ${health.endpoints} endpoints available"
                binding.textServerStatus.setTextColor(themeColor(MAttr.colorPrimary))
                refreshCatalog()
            }.onFailure { err ->
                binding.textServerStatus.text =
                    "Error: ${PiApiClient.friendlyError(err)}"
                binding.textServerStatus.setTextColor(themeColor(MAttr.colorError))
            }
        }
    }

    private fun refreshCatalog() {
        if (prefs.serverBaseUrl.isBlank()) {
            schemaLoadFailed = true
            renderCatalog()
            return
        }

        lifecycleScope.launch {
            val schema = apiClient.getSchema()
            schema.onSuccess { json ->
                schemaLoadFailed = false
                // Write-through: widgets render cache-only, so every
                // successful schema fetch refreshes the widget catalog
                // and re-renders the pinned instances (a widget that was
                // showing "Offline" goes live with the fresh schema).
                StateCache.getInstance(applicationContext).putSchema(json)
                PiWidgets.refreshAll(applicationContext)
                deviceGroups = ControlCatalog.fromSchema(json)
                renderCatalog()
            }.onFailure {
                schemaLoadFailed = true
                if (deviceGroups.isEmpty()) renderCatalog()
                Toast.makeText(this@MainActivity,
                    "Schema load failed: ${PiApiClient.friendlyError(it)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- dynamic device cards ----

    private fun renderCatalog() {
        val container = binding.devicesContainer
        container.removeAllViews()
        uiHolders.clear()

        if (deviceGroups.isEmpty()) {
            container.addView(TextView(this).apply {
                text = when {
                    prefs.serverBaseUrl.isBlank() ->
                        "Enter the server address, then tap Refresh."
                    schemaLoadFailed ->
                        "Can't reach the Pi â€” check the server address, then tap Refresh."
                    else ->
                        "No parameterized actions in the pipeline."
                }
                setTextAppearance(MStyle.TextAppearance_Material3_BodyMedium)
                setTextColor(themeColor(MAttr.colorOnSurfaceVariant))
            })
            renderWidgetList()
            return
        }

        for (group in deviceGroups) {
            container.addView(buildGroupCard(group))
        }
        renderWidgetList()
        refreshAllStates()
    }

    private fun buildGroupCard(group: DeviceGroup): MaterialCardView {
        val card = MaterialCardView(this)
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.bottomMargin = dp(14)
        card.layoutParams = cardParams
        card.radius = dp(12).toFloat()
        card.setContentPadding(dp(16), dp(16), dp(16), dp(16))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(col)

        // Card title (schema path, prettified)
        col.addView(TextView(this).apply {
            text = ControlCatalog.prettifyGroup(group.path)
            setTextAppearance(MStyle.TextAppearance_Material3_TitleMedium)
            setTextColor(themeColor(MAttr.colorOnSurface))
        })

        val rows = mutableMapOf<String, RowUi>()

        group.setAction?.let { spec ->
            for (param in spec.params) {
                when {
                    param.name !in spec.inputs -> Unit  // output-only param: shown in subtitle
                    param.isBool -> {
                        val row = ItemParamSwitchRowBinding.inflate(layoutInflater, col, false)
                        row.titleParam.text = ControlCatalog.prettify(param.name)
                        row.switchParam.setOnCheckedChangeListener { _, checked ->
                            if (row.switchParam.tag == "ui") return@setOnCheckedChangeListener
                            dispatchParam(group, param, if (checked) 1.0 else 0.0)
                        }
                        col.addView(row.root, marginTop(4))
                        rows["param:${param.name}"] = RowUi(null, row.textParamValue, row.switchParam, param)
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
                            val key = "${group.path}:${param.name}"
                            updateJobs[key]?.cancel()
                            updateJobs[key] = lifecycleScope.launch {
                                delay(300)  // debounce: latest-wins upstream too
                                dispatchParam(group, param, value.toDouble())
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

        if (group.legacy.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = "Actions"
                setTextAppearance(MStyle.TextAppearance_Material3_LabelMedium)
                setTextColor(themeColor(MAttr.colorOnSurfaceVariant))
            }, marginTop(16))
            val flow = FlowRowLayout(this)
            for (action in group.legacy) {
                val btn = ItemLegacyActionButtonBinding.inflate(layoutInflater, flow, false).root
                btn.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                }
                btn.text = action.title
                btn.setOnClickListener {
                    lifecycleScope.launch {
                        apiClient.dispatchLegacy(action.path)
                            .onSuccess {
                                Toast.makeText(this@MainActivity,
                                    "${action.title}: done", Toast.LENGTH_SHORT).show()
                                // Buttons (esp. group resets) change other groups.
                                PiStateSync.scheduleAfterAction(applicationContext)
                            }
                            .onFailure {
                                Toast.makeText(this@MainActivity,
                                    "${action.title}: failed (${it.message})", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                flow.addView(btn)
            }
            col.addView(flow, marginTop(8))
        }

        uiHolders[group.path] = GroupCardUi(col, rows)
        return card
    }

    // ---- home-screen widget list + pin flow ----

    /**
     * Every addable widget built from the schema: one toggle widget per
     * group whose set action has a bool param, and one button widget per
     * legacy no-arg action. Renders from the live schema, falling back to
     * the cached catalog offline.
     */
    private fun renderWidgetList() {
        val container = binding.widgetsContainer
        container.removeAllViews()

        updatePlacedWidgetsRow()

        val groups = if (deviceGroups.isNotEmpty()) deviceGroups else PiWidgets.catalog(this)
        val entries = mutableListOf<WidgetEntry>()
        for (group in groups) {
            group.setAction?.toggleParam?.let { toggle ->
                entries += WidgetEntry(
                    title = ControlCatalog.prettifyGroup(group.path),
                    subtitle = "Live state widget Â· dot shows On/Off",
                    iconPath = group.setAction?.path,
                    iconCandidates = arrayOf(
                        group.legacy.firstOrNull()?.path,
                        group.setAction?.path, group.path),
                    extras = PiAppWidgetProvider.bindingExtras(
                        PiAppWidgetProvider.KIND_TOGGLE,
                        group.path, toggle.name, null))
            }
            for (action in group.legacy) {
                entries += WidgetEntry(
                    title = action.title,
                    subtitle = "One-shot run Â· no state shown",
                    iconPath = action.path,
                    iconCandidates = arrayOf(action.path),
                    extras = PiAppWidgetProvider.bindingExtras(
                        PiAppWidgetProvider.KIND_BUTTON,
                        group.path, null, action.path))
            }
        }

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Addable widgets appear here once a schema is available."
                setTextAppearance(MStyle.TextAppearance_Material3_BodySmall)
                setTextColor(themeColor(MAttr.colorOnSurfaceVariant))
            })
            return
        }

        for (entry in entries) {
            val row = ItemWidgetRowBinding.inflate(layoutInflater, container, false)
            row.textWidgetTitle.text = entry.title
            row.textWidgetSubtitle.text = entry.subtitle
            loadWidgetThumb(row.imgWidgetThumb, entry)
            row.btnAddWidget.setOnClickListener { requestPinAppWidget(entry) }
            container.addView(row.root)
        }
    }

    /** Management row for already-placed widgets. Widget ids are owned by
     * the launcher host â€” no public API lets the provider app delete them â€”
     * so this only informs: how many instances are registered, and that
     * removal happens on the home screen itself (long-press a tile). */
    private fun updatePlacedWidgetsRow() {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager?.getAppWidgetIds(
            ComponentName(this, PiAppWidgetProvider::class.java)) ?: IntArray(0)
        binding.placedRow.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        binding.textPlacedCount.text =
            "${ids.size} installed on your home screen. To remove one, long-press the tile there."
    }

    /** Icon thumbnail straight from the shared widget icon cache. Toggle
     * rows walk the same icon ladder as the widgets themselves (first
     * legacy action, set path, group path); button rows use their action. */
    private fun loadWidgetThumb(view: android.widget.ImageView, entry: WidgetEntry) {
        val iconPath = entry.iconPath
        view.setImageResource(R.drawable.ic_widget_generic)
        view.tag = iconPath
        if (iconPath == null) return
        PiWidgetIcons.loadFirst(context = applicationContext, candidates = entry.iconCandidates) { bitmap ->
            // Callback arrives on the icon loader's IO thread; guard against
            // the row having been re-rendered for a different path.
            runOnUiThread {
                if (bitmap != null && view.tag == iconPath) view.setImageBitmap(bitmap)
            }
        }
    }

    /** System pin request carrying the pin-time binding in a unique
     * success PendingIntent. Launchers without pin support get guidance
     * (inline text + toast) instead of a failed dialog. */
    private fun requestPinAppWidget(entry: WidgetEntry) {
        if (!this@MainActivity::binding.isInitialized) return
        val manager = AppWidgetManager.getInstance(this) ?: return
        val provider = ComponentName(this, PiAppWidgetProvider::class.java)
        if (!manager.isRequestPinAppWidgetSupported) {
            binding.textWidgetGuidance.visibility = View.VISIBLE
            Toast.makeText(this, "Long-press your home screen to add widgets", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Nonce + unique request code: two consecutive "Add" taps for
            // the same widget kind produce identical intent signatures,
            // which PendingIntent would collapse into one â€” the second
            // success would silently replace the first. The nonce extra
            // differentiates the fill-in intents; the unique request code
            // keeps the PendingIntents distinct as well.
            val nonce = java.util.UUID.randomUUID().toString()
            val requestCode = (nonce.hashCode() and 0x7fffffff)
            val successIntent = Intent(this, PiAppWidgetProvider::class.java).apply {
                action = PiAppWidgetProvider.ACTION_PIN_SUCCESS
                putExtra(PiAppWidgetProvider.EXTRA_PIN_NONCE, nonce)
                putExtras(entry.extras)
            }
            // MUTABLE: the launcher must fill EXTRA_APPWIDGET_ID in via
            // fill-in intent; immutable PendingIntents can't receive it.
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE else 0
            val success = android.app.PendingIntent.getBroadcast(
                this, requestCode, successIntent, flags)
            val accepted = manager.requestPinAppWidget(provider, entry.extras, success)
            if (!accepted) {
                // Some launchers report support yet reject the request.
                binding.textWidgetGuidance.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Pin failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchParam(group: DeviceGroup, param: ParamSpec, value: Double) {
        val spec = group.setAction ?: return
        val body = mapOf<String, Any?>(
            param.name to when (param.type) {
                "int" -> value.toInt()
                "bool" -> value > 0.5
                else -> value
            })
        android.util.Log.i("PiDA", "dispatch ${spec.path} body=$body")
        lifecycleScope.launch {
            apiClient.dispatch(spec.path, body)
                .onSuccess { state ->
                    applyState(group, state)
                    // Latent all-group settle check (fan-out actions change
                    // other groups; some devices reflect late).
                    PiStateSync.scheduleAfterAction(applicationContext)
                }
                .onFailure {
                    android.util.Log.w("PiDA", "dispatch ${spec.path} failed: ${it.message}")
                    // Revert the row to the last known real state â€” the
                    // switch/slider the user moved may not match reality.
                    groupStates[group.path]?.let { applyState(group, it) }
                    Toast.makeText(this@MainActivity,
                        "${param.name}: ${PiApiClient.friendlyError(it)}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun refreshAllStates() {
        for (group in deviceGroups) {
            val spec = group.setAction ?: continue
            if (spec.outputs.isEmpty()) continue
            refreshGroup(group)
        }
    }

    private fun refreshGroup(group: DeviceGroup) {
        val spec = group.setAction ?: return
        lifecycleScope.launch {
            apiClient.getState(spec.path).onSuccess { state ->
                applyState(group, state)
            }
        }
    }

    private fun applyState(group: DeviceGroup, state: Map<String, Any?>) {
        groupStates[group.path] = state
        // Write-through: the Device Controls panel publishes from this cache.
        StateCache.getInstance(this).putGroupState(group.path, state)
        val ui = uiHolders[group.path] ?: return
        for ((key, row) in ui.rows) {
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

    private fun setupDeviceControlsButton() {
        binding.btnOpenDeviceControls.setOnClickListener {
            val intent = Intent("android.settings.ACTION_DEVICE_CONTROLS_SETTINGS")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
