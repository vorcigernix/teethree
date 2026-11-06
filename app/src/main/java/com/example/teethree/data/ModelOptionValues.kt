package com.example.teethree.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun providerOptionValue(descriptor: JsonObject, selections: JsonArray?): JsonPrimitive? {
    val explicit =
        selections
            ?.filterIsInstance<JsonObject>()
            ?.lastOrNull { it.string("id") == descriptor.string("id") }
            ?.get("value") as? JsonPrimitive
    fun valid(value: JsonPrimitive?): Boolean =
        when (descriptor.string("type")) {
            "boolean" -> value != null && !value.isString && value.booleanOrNull != null
            "select" ->
                value?.isString == true &&
                    descriptor.array("options").filterIsInstance<JsonObject>().any {
                        it.string("id") == value.content
                    }
            else -> false
        }
    if (valid(explicit)) return explicit
    val current = descriptor["currentValue"] as? JsonPrimitive
    if (valid(current)) return current
    return if (descriptor.string("type") == "select")
        descriptor
            .array("options")
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.bool("isDefault") }
            ?.get("id") as? JsonPrimitive
    else null
}

internal fun updateProviderOption(
    descriptor: JsonObject,
    selections: JsonArray?,
    value: JsonPrimitive?,
): JsonArray? {
    val id = descriptor.string("id")
    require(id.isNotBlank()) { "This model option has no identifier." }
    if (value != null)
        require(
            when (descriptor.string("type")) {
                "boolean" -> !value.isString && value.booleanOrNull != null
                "select" ->
                    value.isString &&
                        descriptor.array("options").filterIsInstance<JsonObject>().any {
                            it.string("id") == value.content
                        }
                else -> false
            }
        ) {
            "Choose a supported model option."
        }
    val remaining = selections.orEmpty().filterNot { (it as? JsonObject)?.string("id") == id }
    val next =
        if (value == null) remaining
        else
            remaining +
                buildJsonObject {
                    put("id", id)
                    put("value", value)
                }
    return next.takeIf { it.isNotEmpty() }?.let(::JsonArray)
}
