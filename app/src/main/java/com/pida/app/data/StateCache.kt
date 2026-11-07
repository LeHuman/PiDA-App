package com.pida.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent cache for Device Controls state (schema JSON, per-group state
 * maps, legacy button states). Survives process death so the One UI panel
 * can publish cached controls instantly instead of showing "Loading" while
 * the first network round trip completes.
 *
 * Singleton like [PreferencesManager]; all read-modify-write access is
 * guarded by a single lock for thread safety. Serialization uses org.json
 * (platform API, no extra dependency) with a small recursive converter —
 * org.json treats a raw `null` passed to `put` as "remove the key", so null
 * values are wrapped as `JSONObject.NULL` before insertion.
 */
class StateCache private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    // ---- schema ----

    /** Raw schema JSON previously stored from a successful /api/schema fetch. */
    fun getSchemaJson(): String? = synchronized(lock) { prefs.getString(KEY_SCHEMA, null) }

    fun putSchemaJson(raw: String) = synchronized(lock) {
        prefs.edit().putString(KEY_SCHEMA, raw).apply()
    }

    /** Serialize a fetched schema map (Gson LinkedTreeMaps included) to JSON. */
    fun putSchema(map: Map<String, Any?>) {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, toJSONValue(v))
        putSchemaJson(json.toString())
    }

    // ---- per-group state ----

    fun putGroupState(path: String, state: Map<String, Any?>) = synchronized(lock) {
        val json = JSONObject()
        for ((k, v) in state) json.put(k, toJSONValue(v))
        prefs.edit().putString(GROUP_PREFIX + path, json.toString()).apply()
    }

    fun getGroupState(path: String): Map<String, Any?>? = synchronized(lock) {
        val raw = prefs.getString(GROUP_PREFIX + path, null) ?: return null
        try {
            parseObject(raw).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun getAllGroupStates(): Map<String, Map<String, Any?>> = synchronized(lock) {
        val out = linkedMapOf<String, Map<String, Any?>>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(GROUP_PREFIX) && value is String) {
                try {
                    val parsed = parseObject(value)
                    if (parsed.isNotEmpty()) out[key.removePrefix(GROUP_PREFIX)] = parsed
                } catch (e: Exception) {
                    // Corrupt entry: skip rather than fail the whole seed.
                }
            }
        }
        out
    }

    // ---- widget bindings (pin-success persistence) ----

    /**
     * Persist one pinned widget's binding (appWidgetId → schema binding).
     * Launchers are not required to merge pin-time extras into
     * getAppWidgetOptions, so MainActivity resolves the binding at pin
     * time, carries it in the success PendingIntent, and the provider
     * stores it here on delivery. Read back in [com.pida.app.PiWidgets.readBinding].
     */
    fun putWidgetBinding(id: Int, binding: Map<String, String>) = synchronized(lock) {
        val json = JSONObject()
        for ((k, v) in binding) json.put(k, v)
        prefs.edit().putString(WIDGET_PREFIX + id, json.toString()).apply()
    }

    /** Binding keys are the provider's EXTRA_* constants; null if never pinned. */
    fun getWidgetBinding(id: Int): Map<String, String>? = synchronized(lock) {
        val raw = prefs.getString(WIDGET_PREFIX + id, null) ?: return null
        try {
            parseObject(raw).mapNotNull { (k, v) ->
                if (v is String && v.isNotBlank()) k to v else null
            }.toMap().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Mirrored on the provider's onDelete so deleted widgets leave no residue. */
    fun deleteWidgetBinding(id: Int) = synchronized(lock) {
        prefs.edit().remove(WIDGET_PREFIX + id).apply()
    }

    // ---- legacy buttons (optimistic toggles) ----

    fun putButtonState(path: String, value: Boolean) = synchronized(lock) {
        prefs.edit().putBoolean(BUTTON_PREFIX + path, value).apply()
    }

    fun getAllButtonStates(): Map<String, Boolean> = synchronized(lock) {
        val out = linkedMapOf<String, Boolean>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(BUTTON_PREFIX) && value is Boolean) {
                out[key.removePrefix(BUTTON_PREFIX)] = value
            }
        }
        out
    }

    // ---- helpers ----

    /** Parse a JSON object string into Map<String, Any?> (nested via fromJSON). */
    fun parseObject(raw: String): Map<String, Any?> =
        (fromJSON(JSONObject(raw)) as? Map<String, Any?>) ?: emptyMap()

    /**
     * Recursive Any? → JSONValue converter. Handles Gson's LinkedTreeMap
     * (Map), Kotlin collections/arrays, primitives and null.
     */
    private fun toJSONValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Boolean, is Number, is String -> value
        is Map<*, *> -> JSONObject().apply {
            for ((k, v) in value) put(k.toString(), toJSONValue(v))
        }
        is Iterable<*> -> JSONArray().apply {
            for (v in value) put(toJSONValue(v))
        }
        is Array<*> -> JSONArray().apply {
            for (v in value) put(toJSONValue(v))
        }
        else -> value.toString()
    }

    /** Recursive JSONValue → Any? (JSONObject→LinkedHashMap, JSONArray→List). */
    private fun fromJSON(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            for (key in value.keys()) put(key, fromJSON(value.opt(key)))
        }
        is JSONArray -> (0 until value.length())
            .map { fromJSON(value.get(it)) }
            .toMutableList()
        else -> value
    }

    companion object {
        private const val PREFS_NAME = "pi_state_cache"
        private const val KEY_SCHEMA = "schema_json"
        private const val GROUP_PREFIX = "group_state:"
        private const val BUTTON_PREFIX = "btn:"
        private const val WIDGET_PREFIX = "widget_binding:"

        @Volatile
        private var instance: StateCache? = null

        fun getInstance(context: Context): StateCache {
            return instance ?: synchronized(this) {
                instance ?: StateCache(context.applicationContext).also { instance = it }
            }
        }
    }
}
