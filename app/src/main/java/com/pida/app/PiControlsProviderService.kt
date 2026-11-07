package com.pida.app

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import com.pida.app.data.ControlCatalog
import com.pida.app.data.DeviceGroup
import com.pida.app.data.ParamSpec
import com.pida.app.data.PiApiClient
import com.pida.app.data.PreferencesManager
import com.pida.app.data.SetActionSpec
import com.pida.app.data.StateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlin.math.abs

/**
 * Schema-driven Device Controls provider.
 *
 * Control id scheme (stable across catalog rebuilds):
 *   ctl:<group>            main control of a device group (toggle + first range)
 *   ctl:<group>:<param>    extra range control (e.g. temperature)
 *   btn:<endpoint path>    legacy no-arg action button (optimistic toggle,
 *                          no state verification — user decision)
 *
 * State for a group comes from the parameterized action's GET-as-state
 * readback (empty delta = the state read on the Pi). Dispatches POST a
 * one-key partial delta; the server clamps and serializes per device lane.
 *
 * Publish-from-cache-first: the catalog, group states and button states are
 * persisted in [StateCache] and restored in onCreate, so panels receive
 * cached controls with zero network before a background refresh republishes
 * fresh values. Error controls only appear where no state was ever cached.
 */
class PiControlsProviderService : ControlsProviderService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private lateinit var apiClient: PiApiClient
    private lateinit var stateCache: StateCache

    // Live subscribers keyed by control id. Kept open (no onComplete) so that
    // performControlAction can push updated control state back to the system
    // panel; otherwise the panel never learns the new state.
    private val subscribers = mutableMapOf<String, Flow.Subscriber<Control>>()

    private val buttonStates = mutableMapOf<String, Boolean>()
    private val groupStates = mutableMapOf<String, Map<String, Any?>>()

    @Volatile
    private var catalogCache: List<DeviceGroup>? = null
    @Volatile
    private var catalogAt = 0L

    private suspend fun catalog(): List<DeviceGroup> {
        val cached = catalogCache
        if (cached != null && SystemClock.elapsedRealtime() - catalogAt < CATALOG_TTL_MS) {
            return cached
        }
        val fetched = apiClient.getSchema().getOrNull() ?: return cached ?: emptyList()
        stateCache.putSchema(fetched)
        return ControlCatalog.fromSchema(fetched).also {
            catalogCache = it
            catalogAt = SystemClock.elapsedRealtime()
        }
    }

    private fun publishControl(controlId: String, control: Control) {
        synchronized(subscribers) {
            val sub = subscribers[controlId]
            if (sub == null) {
                android.util.Log.w("PiControls", "push dropped (no subscriber): " + controlId)
                return
            }
            try {
                sub.onNext(control)
                android.util.Log.i("PiControls", "push sent: " + controlId)
            } catch (e: Exception) {
                android.util.Log.w("PiControls", "push failed (subscriber removed): " + controlId + ", " + e)
                subscribers.remove(controlId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("PiControls", "PiControlsProviderService onCreate")
        instance = this
        prefs = PreferencesManager.getInstance(this)
        apiClient = PiApiClient.getInstance(prefs)
        stateCache = StateCache.getInstance(this)
        seedCachesFromDisk()
    }

    /** Restore the last known catalog/state so panels publish from cache. */
    private fun seedCachesFromDisk() {
        val raw = stateCache.getSchemaJson()
        if (!raw.isNullOrBlank()) {
            try {
                val schema = stateCache.parseObject(raw)
                if (schema.isNotEmpty()) {
                    val groups = ControlCatalog.fromSchema(schema)
                    if (groups.isNotEmpty()) {
                        catalogCache = groups
                        // catalogAt stays 0: the next background refresh
                        // revalidates the schema against the Pi.
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PiControls", "schema cache unparsable: ${e.message}")
            }
        }
        groupStates.putAll(stateCache.getAllGroupStates())
        buttonStates.putAll(stateCache.getAllButtonStates())
    }

    /** Single write-through funnel for a group's state readback. */
    private fun storeState(path: String, state: Map<String, Any?>) {
        groupStates[path] = state
        stateCache.putGroupState(path, state)
        // Widgets publish from the same cache; re-render every pinned id
        // (cheap, no network). Exception-safe per contract.
        PiWidgets.refreshAll(applicationContext)
    }

    /** Body-tap reconciliation for an open panel: push the just-dispatched
     * control's persisted state through the live subscription. */
    fun ingestTapResult(controlId: String) {
        when {
            controlId.startsWith("btn:") -> {
                val path = controlId.removePrefix("btn:")
                buttonStates[path] = stateCache.getAllButtonStates()[path] ?: return
                val group = catalogCache?.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == path } }
                    ?: return
                controlById(controlId, group, null)?.let { publishControl(controlId, it) }
            }
            controlId.startsWith("ctl:") -> {
                val path = controlId.removePrefix("ctl:")
                val group = catalogCache?.firstOrNull { it.path == path } ?: return
                // Keep the in-memory map in step with prefs: other publish
                // paths (all-available listing, background refresh fallback)
                // still read from it.
                stateCache.getGroupState(path)?.let { groupStates[path] = it }
                publishGroup(group, stateCache.getGroupState(path))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.i("PiControls", "PiControlsProviderService onDestroy")
        synchronized(subscribers) { subscribers.clear() }
        serviceScope.cancel()
        instance = null
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        android.util.Log.i("PiControls", "createPublisherForAllAvailable called")
        return Flow.Publisher { subscriber ->
            subscriber.onSubscribe(object : Flow.Subscription {
                private var cancelled = false
                override fun request(n: Long) {
                    if (cancelled) return
                    val cachedGroups = catalogCache
                    serviceScope.launch {
                        if (cachedGroups != null) {
                            // Cache-first: no network before onComplete.
                            for (group in cachedGroups) {
                                val state = groupStates[group.path]
                                for (control in controlsFor(group, state, stateless = true)) {
                                    subscriber.onNext(control)
                                }
                            }
                            subscriber.onComplete()
                            // Refresh the cache for next time; the subscription
                            // is already complete, so no further pushes.
                            catalog()
                        } else {
                            val groups = catalog()
                            if (groups.isEmpty()) {
                                subscriber.onNext(statelessControl("offline", "Pi server offline", "No schema", 0))
                                subscriber.onComplete()
                                return@launch
                            }
                            for (group in groups) {
                                val state = groupStates[group.path]
                                for (control in controlsFor(group, state, stateless = true)) {
                                    subscriber.onNext(control)
                                }
                            }
                            subscriber.onComplete()
                        }
                    }
                }
                override fun cancel() {
                    cancelled = true
                }
            })
        }
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> {
        android.util.Log.i("PiControls", "createPublisherFor called: $controlIds")
        return Flow.Publisher { subscriber ->
            val subRef = subscriber as Flow.Subscriber<Control>
            subRef.onSubscribe(object : Flow.Subscription {
                @Volatile
                private var cancelled = false

                override fun request(n: Long) {
                    if (cancelled) return
                    serviceScope.launch { publishForIds(controlIds, subRef) }
                }

                override fun cancel() {
                    cancelled = true
                    synchronized(subscribers) {
                        controlIds.forEach { id -> if (subscribers[id] === subRef) subscribers.remove(id) }
                    }
                }
            })
        }
    }

    /**
     * Publish-from-cache-first: the subscriber is registered before anything
     * else and immediately receives controls built from the persistent cache
     * (zero network on the critical path). Only then does the background
     * refresh fetch fresh schema/state and republish through the regular
     * publish path.
     */
    private suspend fun publishForIds(controlIds: List<String>, subscriber: Flow.Subscriber<Control>) {
        android.util.Log.i("PiControls", "publishForIds: ids=${controlIds.size} cachedCatalog=${catalogCache != null} cachedStates=${groupStates.size} btns=${buttonStates.size}")
        synchronized(subscribers) { controlIds.forEach { subscribers[it] = subscriber } }

        val cachedGroups = catalogCache
        if (cachedGroups == null) {
            // Cold cache (first run / nothing persisted yet): today's
            // behavior — network before the first onNext.
            publishFromNetwork(controlIds, subscriber)
            return
        }

        // Synchronous cached publish — no network, so the panel renders
        // instantly instead of showing "Loading".
        publishCachedControls(controlIds, cachedGroups, subscriber)

        // Then the background refresh pushes updated controls through
        // publishControl/publishGroup (which handle dead subscribers).
        refreshAndRepublish(controlIds)
    }

    /** Cold-cache fallback: schema fetch first, then tiles immediately and
     * states streamed per-group in parallel — one slow readback (e.g. the
     * monitor with the TV off) delays only its own tile, never the panel. */
    private suspend fun publishFromNetwork(controlIds: List<String>, subscriber: Flow.Subscriber<Control>) {
        val groups = catalog()
        if (groups.isEmpty()) {
            controlIds.forEach { subscriber.onNext(buildErrorControl(it, "Pi server offline")) }
            subscriber.onComplete()
            return
        }

        // Publish every requested id at once (state unknown ? "—" subtitle)
        // with zero further network on the critical path.
        for (id in controlIds) {
            when {
                id.startsWith("ctl:") -> {
                    val group = parseCtl(id, groups)?.group
                    if (group == null) {
                        subscriber.onNext(buildErrorControl(id, "Removed from pipeline"))
                    } else {
                        subscriber.onNext(
                            controlById(id, group, null) ?: buildErrorControl(id, group.title)
                        )
                    }
                }
                id.startsWith("btn:") -> {
                    val group = groups.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == id } }
                    if (group == null) {
                        subscriber.onNext(buildErrorControl(id, "Removed from pipeline"))
                    } else {
                        controlById(id, group, null)?.let { subscriber.onNext(it) }
                    }
                }
                // Any requested id we cannot resolve (stale favorites from an
                // older control scheme) must still answer, or the panel spins.
                else -> subscriber.onNext(buildErrorControl(id, "Removed from pipeline"))
            }
        }

        // Stream each group's state in parallel: on completion, persist and
        // push that group's tiles through publishControl (dead subscribers
        // pruned there). Failures leave the unknown-state tile — no blocking.
        for (group in groups.distinctBy { it.path }) {
            val spec = group.setAction ?: continue
            if (spec.outputs.isEmpty()) continue
            serviceScope.launch {
                apiClient.getState(spec.path)
                    .onSuccess { state ->
                        storeState(group.path, state)
                        publishGroup(group, state)
                    }
            }
        }
    }

    /** Zero-network publish of the requested ids from the cached catalog. */
    private fun publishCachedControls(
        controlIds: List<String>,
        groups: List<DeviceGroup>,
        subscriber: Flow.Subscriber<Control>
    ) {
        val ctlIds = controlIds.filter { it.startsWith("ctl:") }
        for ((_, ids) in ctlIds.groupBy { parseCtl(it, groups)?.group }) {
            val group = parseCtl(ids.first(), groups)?.group
            if (group == null) {
                ids.forEach { subscriber.onNext(buildErrorControl(it, "Removed from pipeline")) }
                continue
            }
            // Read the persistent cache (single source of truth): body-tap
            // dispatches and the app write through there, and the in-memory
            // map can be stale or empty after a service restart.
            val state = stateCache.getGroupState(group.path)
            for (id in ids) {
                subscriber.onNext(controlById(id, group, state) ?: buildErrorControl(id, group.title))
            }
        }

        for (id in controlIds.filter { it.startsWith("btn:") }) {
            val group = groups.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == id } }
            if (group == null) {
                subscriber.onNext(buildErrorControl(id, "Removed from pipeline"))
                continue
            }
            controlById(id, group, null)?.let { subscriber.onNext(it) }
        }

        // Stale ids from an older control scheme still answer.
        for (id in controlIds) {
            if (id.startsWith("ctl:") || id.startsWith("btn:")) continue
            subscriber.onNext(buildErrorControl(id, "Removed from pipeline"))
        }
    }

    /**
     * Background refresh after the cached publish. Fetches the schema (only
     * when the catalog TTL expired — see CATALOG_TTL_MS) and fresh state for
     * every requested group whose setAction has outputs, republishing
     * through publishControl/publishGroup so dead subscribers are pruned.
     * On fetch failure the cached controls stay on the panel: the cached
     * pass showed schema-default tiles (assumed off / "—") where nothing
     * was cached, and those are corrected by this refresh when it lands.
     */
    private suspend fun refreshAndRepublish(controlIds: List<String>) {
        val before = catalogCache
        val groups = catalog()
        val catalogChanged = catalogCache != null && catalogCache !== before
        // On schema change, republish everything currently subscribed —
        // ids may have moved groups, gained params, or vanished.
        val targets = if (catalogChanged) {
            synchronized(subscribers) { subscribers.keys.toList() }
        } else controlIds

        if (catalogChanged) {
            for (id in targets.filter { it.startsWith("btn:") }) {
                val group = groups.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == id } }
                if (group == null) {
                    publishControl(id, buildErrorControl(id, "Removed from pipeline"))
                    continue
                }
                controlById(id, group, null)?.let { publishControl(id, it) }
            }
        }

        // Group the ctl ids by target group so each group is fetched once.
        val idsByPath = linkedMapOf<String, MutableList<String>>()
        for (id in targets.filter { it.startsWith("ctl:") }) {
            val group = parseCtl(id, groups)?.group
            if (group == null) {
                publishControl(id, buildErrorControl(id, "Removed from pipeline"))
                continue
            }
            val spec = group.setAction
            if (spec != null && spec.outputs.isNotEmpty()) {
                idsByPath.getOrPut(group.path) { mutableListOf() }.add(id)
            } else if (catalogChanged) {
                // No state to read back; only republish when the template
                // definition may have changed with the schema.
                controlById(id, group, groupStates[group.path])?.let { publishControl(id, it) }
            }
        }

        for ((path, ids) in idsByPath) {
            val group = groups.firstOrNull { it.path == path } ?: continue
            val spec = group.setAction ?: continue
            apiClient.getState(spec.path)
                .onSuccess { state ->
                    storeState(path, state)
                    android.util.Log.i("PiControls", "state refresh for $path ok")
                    publishGroup(group, state)
                }
                .onFailure {
                    // Keep the cached controls on the panel; error controls
                    // were already published by the cached pass where no
                    // state was cached at all.
                    android.util.Log.w("PiControls", "state refresh for $path failed: ${it.message}")
                }
        }
    }

    override fun performControlAction(
        controlId: String,
        action: ControlAction,
        consumer: Consumer<Int>
    ) {
        android.util.Log.i("PiControls", "performControlAction: $controlId, $action")
        serviceScope.launch {
            try {
                when {
                    controlId.startsWith("btn:") -> handleButton(controlId, action, consumer)
                    controlId.startsWith("ctl:") -> handleCtl(controlId, action, consumer)
                    else -> consumer.accept(ControlAction.RESPONSE_FAIL)
                }
            } catch (e: Exception) {
                android.util.Log.w("PiControls", "action failed: $e")
                consumer.accept(ControlAction.RESPONSE_FAIL)
            }
        }
    }

    private suspend fun handleButton(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val path = controlId.removePrefix("btn:")
        val newState = !(buttonStates[path] ?: false)
        // Optimistic flip (button semantics: no state verification) — the
        // press answers OK instantly so rapid re-taps just queue on the Pi's
        // per-group lane; a failed dispatch flips the tile back.
        buttonStates[path] = newState
        stateCache.putButtonState(path, newState)
        catalogCache?.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == path } }
            ?.let { group -> controlById(controlId, group, null)?.let { publishControl(controlId, it) } }
        consumer.accept(ControlAction.RESPONSE_OK)

        val result = apiClient.dispatchLegacy(path)
        result.onSuccess {
            // Buttons (esp. group resets) change groups beyond their own.
            PiStateSync.scheduleAfterAction(this@PiControlsProviderService)
        }
        if (result.isFailure) {
            buttonStates[path] = !newState
            stateCache.putButtonState(path, !newState)
            catalogCache?.firstOrNull { g -> g.legacy.any { "btn:${it.path}" == path } }
                ?.let { group -> controlById(controlId, group, null)?.let { publishControl(controlId, it) } }
        }
    }

    private suspend fun handleCtl(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val groups = catalog()
        val ref = parseCtl(controlId, groups) ?: run {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        val spec = ref.group.setAction ?: run {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }

        val body = mutableMapOf<String, Any?>()
        when (action) {
            is BooleanAction -> {
                val toggle = spec.toggleParam ?: run {
                    consumer.accept(ControlAction.RESPONSE_FAIL)
                    return
                }
                body[toggle.name] = action.newState
            }
            is FloatAction -> {
                val param = spec.rangeParams.firstOrNull { it.name == ref.extraParam }
                    ?: spec.rangeParams.firstOrNull() ?: run {
                        consumer.accept(ControlAction.RESPONSE_FAIL)
                        return
                    }
                body[param.name] = if (param.type == "int") action.newValue.toInt() else action.newValue.toDouble()

            }
            else -> {
                consumer.accept(ControlAction.RESPONSE_FAIL)
                return
            }
        }

        // Optimistic commit: reflect the assumed new state and answer OK at
        // once so the tile is re-tappable immediately (remote semantics).
        // The real dispatch continues below; its true-state readback
        // corrects the tile, and a failed dispatch snaps back.
        val previous = groupStates[ref.group.path]
        val assumed = (previous ?: emptyMap<String, Any?>()).toMutableMap()
        body.forEach { (k, v) -> assumed[k] = v }
        groupStates[ref.group.path] = assumed
        publishGroup(ref.group, assumed)
        consumer.accept(ControlAction.RESPONSE_OK)

        val result = apiClient.dispatch(spec.path, body)
        result.onSuccess { outputs ->
            // The server's dispatch contract settles the readback before
            // answering (pipeline converge loop), so this is final truth:
            // cache it and push the tile in one hop.
            storeState(ref.group.path, outputs)
            publishGroup(ref.group, outputs)
            // Latent all-group settle check (fan-out actions change other
            // groups; some devices reflect late).
            PiStateSync.scheduleAfterAction(this@PiControlsProviderService)
        }
        if (result.isFailure) {
            // Snap back to the last real state (assumed defaults if we
            // never had one), then re-read the truth.
            if (previous != null) groupStates[ref.group.path] = previous
            else groupStates.remove(ref.group.path)
            publishGroup(ref.group, previous)
            apiClient.getState(spec.path)
                .onSuccess { state ->
                    storeState(ref.group.path, state)
                    publishGroup(ref.group, state)
                }
        }
    }

    private fun publishGroup(group: DeviceGroup, state: Map<String, Any?>?) {
        val controls = controlsFor(group, state, stateless = false)
        for (control in controls) {
            publishControl(control.controlId, control)
        }
    }

    private fun controlById(id: String, group: DeviceGroup, state: Map<String, Any?>?): Control? =
        controlsFor(group, state, stateless = false).firstOrNull { it.controlId == id }

    // ---- template building ----

    private fun controlsFor(
        group: DeviceGroup,
        state: Map<String, Any?>?,
        stateless: Boolean
    ): List<Control> {
        val out = mutableListOf<Control>()
        val spec = group.setAction
        val subtitle = if (spec != null) stateSubtitle(spec, state) else "Action"

        if (spec != null) {
            val main = mainTemplateFor(group, spec, state)
            val mainSubtitle = if (spec.outputs.isEmpty()) group.title else subtitle
            if (main != null) {
                out += statefulControl("ctl:${group.path}", group.title, mainSubtitle,
                    group.deviceType, main, stateless, bodyToggles = spec.toggleParam != null)
            } else {
                // Outputs-only action: read-only control showing real state.
                out += statefulControl("ctl:${group.path}", group.title, subtitle,
                    group.deviceType, StatelessTemplate("tpl_state_${group.path}"), stateless)
            }
            for (extra in spec.rangeParams.drop(1)) {
                out += statefulControl(
                    "ctl:${group.path}:${extra.name}", extraLabel(extra.name),
                    valueLabel(extra, state), group.deviceType,
                    rangeTemplate(group, extra, state), stateless)
            }
        }
        for (btn in group.legacy) {
            val on = buttonStates[btn.path] ?: false
            out += statefulControl(
                "btn:${btn.path}", btn.title, "Tap to run · ${group.title}",
                group.deviceType, ToggleTemplate("tpl_btn_${btn.path}", ControlButton(on, "dispatch")),
                stateless, bodyToggles = true)
        }
        return out
    }

    private fun mainTemplateFor(
        group: DeviceGroup,
        spec: SetActionSpec,
        state: Map<String, Any?>?
    ): ControlTemplate? {
        val toggle = spec.toggleParam
        val range = spec.rangeParams.firstOrNull()
        return when {
            toggle != null && range != null -> ToggleRangeTemplate(
                "tpl_main_${group.path}",
                ControlButton(boolOf(state, toggle), toggle.name),
                rangeTemplate(group, range, state)
            )
            toggle != null -> ToggleTemplate(
                "tpl_main_${group.path}",
                ControlButton(boolOf(state, toggle), toggle.name)
            )
            range != null -> rangeTemplate(group, range, state)
            else -> null
        }
    }

    private fun rangeTemplate(group: DeviceGroup, param: ParamSpec, state: Map<String, Any?>?): RangeTemplate {
        val min = param.min ?: 0.0
        val max = param.max ?: 100.0
        val value = numOf(state, param, min).coerceIn(min, max)
        return RangeTemplate(
            "tpl_${group.path}_${param.name}",
            min.toFloat(), max.toFloat(), value.toFloat(),
            chooseStep(min, max, param.type == "float"), formatFor(param)
        )
    }

    private fun chooseStep(min: Double, max: Double, isFloat: Boolean): Float {
        val range = max - min
        if (range <= 0) return 1f
        if (isFloat) return (range / 100.0).coerceAtLeast(0.01).toFloat()
        for (c in doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0)) {
            if (c >= range / 100.0 && abs(range % c) < 1e-9) return c.toFloat()
        }
        return 1f
    }

    private fun formatFor(param: ParamSpec): String = when {
        param.name.contains("temp", ignoreCase = true) -> "%.0f K"
        param.name.contains("bright", ignoreCase = true) -> "%.0f%%"
        else -> "%.0f"
    }

    private fun extraLabel(name: String): String = ControlCatalog.prettify(name)

    private fun valueLabel(param: ParamSpec, state: Map<String, Any?>?): String =
        "${numOf(state, param, param.min ?: 0.0).toInt()}${formatFor(param).removePrefix("%.0f")}"

    private fun stateSubtitle(spec: SetActionSpec, state: Map<String, Any?>?): String {
        if (state == null) return "—"
        val bits = mutableListOf<String>()
        for (name in spec.outputs) {
            val v = state[name] ?: continue
            when {
                v is Boolean -> bits += if (v) "On" else "Off"
                v is Number && name.contains("temp", ignoreCase = true) -> bits += "${v.toInt()} K"
                v is Number && name.contains("bright", ignoreCase = true) -> bits += "${v.toInt()}%"
                v is Number -> bits += "${v.toInt()}"
                else -> bits += v.toString()
            }
        }
        return bits.joinToString(" · ").ifEmpty { "—" }
    }

    private fun numOf(state: Map<String, Any?>?, param: ParamSpec, default: Double): Double =
        (state?.get(param.name) as? Number)?.toDouble() ?: default

    private fun boolOf(state: Map<String, Any?>?, param: ParamSpec): Boolean =
        (state?.get(param.name) as? Boolean) ?: false

    // ---- control builders ----

    private fun statefulControl(
        id: String,
        title: String,
        subtitle: String,
        deviceType: Int,
        template: ControlTemplate,
        stateless: Boolean,
        bodyToggles: Boolean = false
    ): Control = if (stateless) {
        statelessControl(id, title, subtitle, deviceType)
    } else {
        Control.StatefulBuilder(id, getTapPendingIntent(id))
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDeviceType(deviceType)
            .setStatus(Control.STATUS_OK)
            .setControlTemplate(template)
            .build()
    }

    private fun statelessControl(id: String, title: String, subtitle: String, deviceType: Int): Control =
        Control.StatelessBuilder(id, getTapPendingIntent(id))
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDeviceType(deviceType)
            .build()

    private fun buildErrorControl(controlId: String, title: String): Control =
        Control.StatefulBuilder(controlId, getTapPendingIntent(controlId))
            .setTitle(title)
            .setSubtitle("Offline / Unreachable")
            .setStatus(Control.STATUS_NOT_FOUND)
            .setControlTemplate(StatelessTemplate("tpl_err_$controlId"))
            .build()

    /** Body-tap trampoline for toggle-capable tiles: the invisible activity
     * performs the press (see [ControlTapActivity]) instead of opening the
     * app. One UI rejects broadcast PendingIntents on tile bodies. */
    private fun getTapPendingIntent(controlId: String): PendingIntent {
        val intent = Intent(this, ControlTapActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(ControlTapActivity.EXTRA_CONTROL_ID, controlId)
        }
        // makeCustomAnimation(0,0): enforced by the core window manager, it
        // clears the launch animation that One UI otherwise plays as a
        // task-switch (the black flash) when a tile body is tapped.
        val noAnim = android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        return PendingIntent.getActivity(
            this, controlId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            noAnim
        )
    }

    private fun parseCtl(id: String, groups: List<DeviceGroup>): CtlRef? {
        val rest = id.removePrefix("ctl:")
        groups.firstOrNull { it.path == rest }?.let { return CtlRef(it, null) }
        val idx = rest.lastIndexOf(':')
        if (idx == -1) return null
        val g = rest.substring(0, idx)
        val param = rest.substring(idx + 1)
        return groups.firstOrNull { it.path == g }?.let { CtlRef(it, param) }
    }

    private data class CtlRef(val group: DeviceGroup, val extraParam: String?)

    companion object {
        private const val CATALOG_TTL_MS = 60_000L

        /** Live service reference for ControlTapReceiver's body-tap updates. */
        @Volatile
        var instance: PiControlsProviderService? = null
    }
}
