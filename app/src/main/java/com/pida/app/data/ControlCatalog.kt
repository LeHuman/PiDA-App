package com.pida.app.data

import android.service.controls.DeviceTypes

/**
 * Schema-driven device catalog built from GET /api/schema.
 *
 * One DeviceGroup per action-bearing schema group (device lane). A group's
 * `set` action (declared params/inputs/outputs) becomes the interactive
 * surface; legacy no-arg actions become tap-to-dispatch buttons (no state
 * verification, per user decision). Legacy actions of sub-groups
 * (e.g. desk/monitor/brightness) are merged into their parent device card.
 */
data class ParamSpec(
    val name: String,
    val type: String,
    val min: Double? = null,
    val max: Double? = null,
    val enumValues: List<String>? = null,
    val required: Boolean = false
) {
    val isBool: Boolean get() = type == "bool"
    val isNumeric: Boolean get() = type == "int" || type == "float"
}

data class SetActionSpec(
    val group: String,
    val path: String,
    val params: List<ParamSpec>,
    val inputs: List<String>,
    val outputs: List<String>
) {
    /** First bool input — the toggle (or absent). */
    val toggleParam: ParamSpec? get() = params.firstOrNull { it.name in inputs && it.isBool }

    /** Numeric inputs in declaration order — slider(s). */
    val rangeParams: List<ParamSpec> get() = params.filter { it.name in inputs && it.isNumeric }
}

data class LegacyActionSpec(
    val group: String,
    val path: String,
    val title: String
)

data class DeviceGroup(
    val path: String,
    val title: String,
    val deviceType: Int,
    val setAction: SetActionSpec? = null,
    val legacy: List<LegacyActionSpec> = emptyList()
)

object ControlCatalog {

    fun fromSchema(json: Map<String, Any?>): List<DeviceGroup> {
        val groupsRaw = json["groups"] as? Map<String, Any?> ?: return emptyList()

        val groups = linkedMapOf<String, DeviceGroup>()
        val childLegacy = mutableMapOf<String, MutableList<LegacyActionSpec>>()

        for ((groupPath, gRaw) in groupsRaw) {
            val actionsRaw = (gRaw as? Map<String, Any?>)
                ?.get("actions") as? Map<String, Any?> ?: continue
            var setSpec: SetActionSpec? = null
            val legacy = mutableListOf<LegacyActionSpec>()
            for ((name, aRaw) in actionsRaw) {
                val a = aRaw as? Map<String, Any?> ?: continue
                val params = a["params"] as? Map<String, Any?>
                val inputs = a["inputs"] as? List<*>
                if (!params.isNullOrEmpty() && !inputs.isNullOrEmpty()) {
                    if (setSpec == null) setSpec = parseSetAction(groupPath.toString(), name, a)
                } else {
                    val path = "$groupPath/$name"
                    legacy += LegacyActionSpec(groupPath.toString(), path, prettify(name))
                }
            }
            // A group that is itself a sub-group of another group (skipped)
            // is handled below via parentGroup: it doesn't get its own card.
            val parent = parentGroup(groupPath.toString(), groupsRaw.keys)
            if (parent != null) {
                childLegacy.getOrPut(parent) { mutableListOf() } += legacy
            } else {
                groups[groupPath.toString()] =
                    DeviceGroup(groupPath.toString(), prettifyGroup(groupPath.toString()),
                        deviceTypeOf(groupPath.toString()), setSpec, legacy)
            }
        }
        // Merge children legacy actions into their parent device card.
        for ((parent, kids) in childLegacy) {
            val g = groups[parent] ?: continue
            groups[parent] = g.copy(legacy = g.legacy + kids)
        }
        return groups.values.toList()
    }

    private fun parseSetAction(group: String, name: String, a: Map<String, Any?>): SetActionSpec? {
        val paramsRaw = a["params"] as? Map<String, Any?> ?: return null
        val inputs = (a["inputs"] as? List<*>)?.map { it.toString() } ?: return null
        val outputs = (a["outputs"] as? List<*>)?.map { it.toString() } ?: emptyList()
        val params = paramsRaw.mapNotNull { (pname, specRaw) ->
            val s = specRaw as? Map<String, Any?> ?: return@mapNotNull null
            val type = (s["type"] as? String) ?: return@mapNotNull null
            ParamSpec(
                name = pname.toString(),
                type = type,
                min = (s["min"] as? Number)?.toDouble(),
                max = (s["max"] as? Number)?.toDouble(),
                required = (s["required"] as? Boolean) ?: false
            )
        }
        return SetActionSpec(group, "$group/$name", params, inputs, outputs)
    }

    /** The immediate registered group a sub-group path hangs under, if any. */
    private fun parentGroup(path: String, keys: Collection<String>): String? {
        val candidates = keys.filter { it != path && path.startsWith("$it/") }
        return candidates.maxByOrNull { it.length }
    }

    private fun deviceTypeOf(groupPath: String): Int = when (groupPath.substringAfterLast("/").lowercase()) {
        "light", "lamp" -> DeviceTypes.TYPE_LIGHT
        "fan" -> DeviceTypes.TYPE_FAN
        "monitor" -> DeviceTypes.TYPE_TV
        "computer" -> DeviceTypes.TYPE_SWITCH
        else -> DeviceTypes.TYPE_SWITCH
    }

    fun prettify(name: String): String =
        name.split("_", " ").filter { it.isNotBlank() }.joinToString(" ") {
            it.lowercase().replaceFirstChar { c -> c.uppercase() }
        }

    fun prettifyGroup(path: String): String =
        path.split("/").joinToString(" ") { prettify(it) }
}
