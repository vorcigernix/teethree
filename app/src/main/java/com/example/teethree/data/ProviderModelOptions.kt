package com.example.teethree.data

import kotlinx.serialization.json.*

internal fun normalizeProviderOptions(value: JsonElement?): JsonArray? {
    if (value == null || value == JsonNull) return null
    val entries =
        when (value) {
            is JsonArray ->
                value.filterIsInstance<JsonObject>().map { it.string("id") to it["value"] }
            is JsonObject -> value.entries.map { it.key to it.value }
            else -> emptyList()
        }
    return JsonArray(
        entries.mapNotNull { (key, raw) ->
            val id = key.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val primitive = raw as? JsonPrimitive ?: return@mapNotNull null
            val selection =
                if (primitive.isString)
                    primitive.content.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive)
                else primitive.booleanOrNull?.let(::JsonPrimitive)
            selection?.let {
                buildJsonObject {
                    put("id", id)
                    put("value", it)
                }
            }
        }
    )
}

internal fun parseModelSelection(value: JsonObject) =
    ModelSelection(
        value.string("instanceId", value.string("provider")),
        value.string("model"),
        normalizeProviderOptions(value["options"]),
    )

internal fun ModelSelection.wire(): JsonObject = buildJsonObject {
    put("instanceId", instanceId)
    put("model", model)
    options?.let { put("options", it) }
}
