package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.teethree.data.T3Model
import com.example.teethree.data.array
import com.example.teethree.data.nullableString
import com.example.teethree.data.providerOptionValue
import com.example.teethree.data.string
import com.example.teethree.data.updateProviderOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ModelOptionControls(
    model: T3Model,
    selections: JsonArray?,
    enabled: Boolean,
    onChange: (JsonArray?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.optionDescriptors.filterIsInstance<JsonObject>().forEach { descriptor ->
            val type = descriptor.string("type")
            if (type == "select" || type == "boolean") {
                val value = providerOptionValue(descriptor, selections)
                val options =
                    if (type == "boolean") listOf("true" to "On", "false" to "Off")
                    else
                        descriptor.array("options").filterIsInstance<JsonObject>().map {
                            it.string("id") to it.string("label", it.string("id"))
                        }
                val explicit =
                    selections?.filterIsInstance<JsonObject>()?.any {
                        it.string("id") == descriptor.string("id")
                    } == true
                val currentLabel = options.firstOrNull { it.first == value?.content }?.second
                ChoiceField(
                    descriptor.string("label", descriptor.string("id")),
                    if (explicit) currentLabel ?: "Unknown"
                    else currentLabel?.let { "Default · $it" } ?: "Provider default",
                    if (enabled) listOf("" to "Use provider default") + options else emptyList(),
                ) { selected ->
                    val next =
                        if (selected.isEmpty()) null
                        else if (type == "boolean") JsonPrimitive(selected == "true")
                        else JsonPrimitive(selected)
                    onChange(updateProviderOption(descriptor, selections, next))
                }
                descriptor.nullableString("description")?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
