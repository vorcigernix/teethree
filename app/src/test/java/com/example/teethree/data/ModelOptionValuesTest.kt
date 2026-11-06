package com.example.teethree.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelOptionValuesTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun optionEditsPreserveUnknownAndBooleanValuesAndCanRestoreProviderDefault() {
        val descriptor =
            json(
                """{"id":"effort","type":"select","options":[{"id":"low","label":"Low"},{"id":"high","label":"High","isDefault":true}]}"""
            )
        val original =
            Json.parseToJsonElement(
                    """[{"id":"future","value":false},{"id":"effort","value":"low"}]"""
                )
                .jsonArray
        assertEquals(JsonPrimitive("low"), providerOptionValue(descriptor, original))
        val updated = updateProviderOption(descriptor, original, JsonPrimitive("high"))!!
        assertEquals(original.first(), updated.first())
        assertEquals(JsonPrimitive("high"), providerOptionValue(descriptor, updated))
        val inherited = updateProviderOption(descriptor, updated, null)!!
        assertEquals(JsonArray(listOf(original.first())), inherited)
        assertEquals(JsonPrimitive("high"), providerOptionValue(descriptor, inherited))
        assertNull(updateProviderOption(descriptor, JsonArray(listOf(original.last())), null))
        assertThrows(IllegalArgumentException::class.java) {
            updateProviderOption(descriptor, original, JsonPrimitive("invented"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            updateProviderOption(descriptor, original, JsonPrimitive(true))
        }
        val toggle = json("""{"id":"fast","type":"boolean","currentValue":true}""")
        val off = updateProviderOption(toggle, updated, JsonPrimitive(false))!!
        assertEquals(JsonPrimitive(false), providerOptionValue(toggle, off))
        assertEquals(JsonPrimitive(true), providerOptionValue(toggle, null))
        assertThrows(IllegalArgumentException::class.java) {
            updateProviderOption(toggle, original, JsonPrimitive("false"))
        }
    }

    @Test
    fun staleOptionValuesFallBackOnlyToAdvertisedDefaults() {
        val descriptor =
            json(
                """{"id":"effort","type":"select","currentValue":"obsolete","options":[{"id":"high","isDefault":true}]}"""
            )
        assertEquals(
            JsonPrimitive("high"),
            providerOptionValue(
                descriptor,
                JsonArray(listOf(json("""{"id":"effort","value":"obsolete"}"""))),
            ),
        )
        assertNull(
            providerOptionValue(
                json("""{"id":"variant","type":"select","options":[{"id":"high"}]}"""),
                null,
            )
        )
    }
}
