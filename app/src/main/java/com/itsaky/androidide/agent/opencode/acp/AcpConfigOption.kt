package com.itsaky.androidide.agent.opencode.acp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** One selectable value of a config option (e.g. a model, or the Build/Plan agent). */
data class AcpConfigValue(
    val value: String,
    val name: String,
    val description: String? = null,
    val group: String? = null
)

/**
 * A session config option as reported by the agent in `session/new` (`configOptions`) or
 * `config_option_update`. OpenCode reports three: `model` (category "model"), `effort`
 * (category "thought_level", only for models with variants) and `mode` (category "mode",
 * the visible primary agents such as Build and Plan).
 */
data class AcpConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val currentValue: String,
    val values: List<AcpConfigValue>
) {
    val currentName: String
        get() = values.firstOrNull { it.value == currentValue }?.name ?: currentValue

    val isMode: Boolean get() = category == "mode" || id == "mode"
    val isModel: Boolean get() = category == "model" || id == "model"

    fun withCurrentValue(value: String) = copy(currentValue = value)
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

/**
 * Parses the ACP `configOptions` array. Only `select` options are supported. Option values may be
 * flat ({value, name, description}) or grouped ({group, name, options:[...]}), both are handled.
 */
fun parseConfigOptions(array: JsonArray?): List<AcpConfigOption> {
    if (array == null) return emptyList()
    return array.mapNotNull { element ->
        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val type = o.str("type")
        if (type != null && type != "select") return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null

        val values = ArrayList<AcpConfigValue>()
        o.getAsJsonArray("options")?.forEach { item ->
            val v = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val nested = v.getAsJsonArray("options")
            if (nested != null) {
                val groupName = v.str("name") ?: v.str("group")
                nested.forEach { child ->
                    val c = child.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val value = c.str("value") ?: return@forEach
                    values.add(AcpConfigValue(value, c.str("name") ?: value, c.str("description"), groupName))
                }
            } else {
                val value = v.str("value") ?: return@forEach
                values.add(AcpConfigValue(value, v.str("name") ?: value, v.str("description"), null))
            }
        }

        AcpConfigOption(
            id = id,
            name = o.str("name") ?: id,
            category = o.str("category"),
            currentValue = o.str("currentValue") ?: values.firstOrNull()?.value.orEmpty(),
            values = values
        )
    }
}
