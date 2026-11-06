package com.example.teethree.data

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class ProviderModelOptionsTest {
    @Test
    fun `model options survive legacy decoding wire commands and encrypted cache serialization`() {
        val selection =
            parseModelSelection(
                Json.parseToJsonElement(
                        """{"provider":"legacy","instanceId":"current","model":"model","options":{" effort ":" high ","fast":false,"unknown":true,"bad":42}}"""
                    )
                    .jsonObject
            )
        assertEquals("current", selection.instanceId)
        assertEquals(3, selection.options?.size)
        assertEquals(JsonPrimitive(false), selection.options!![1].jsonObject["value"])
        val wire = selection.wire()
        assertEquals(selection.options, wire["options"])
        assertEquals(
            selection,
            Json.decodeFromString<ModelSelection>(Json.encodeToString(selection)),
        )
        assertFalse(ModelSelection("provider", "model").wire().containsKey("options"))
        assertEquals(
            JsonArray(emptyList()),
            ModelSelection("provider", "model", JsonArray(emptyList())).wire()["options"],
        )
        val canonical = normalizeProviderOptions(wire["options"])
        assertEquals(selection.options, canonical)
    }
}
