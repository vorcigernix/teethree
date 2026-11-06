package com.example.teethree.data

import kotlinx.serialization.json.JsonObject

internal fun setupRecoveryMessage(projection: JsonObject): JsonObject =
    projection.array("messages").filterIsInstance<JsonObject>().firstOrNull {
        it.string("role") == "user"
    } ?: error("The original message is not available yet. Reopen the conversation and try again.")
