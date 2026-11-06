package com.example.teethree.data

import kotlinx.serialization.json.JsonObject

internal fun mergeServerConfig(current: JsonObject?, event: JsonObject): JsonObject? =
    when (event.string("type")) {
        "snapshot" -> event.obj("config")
        "providerStatuses",
        "settingsUpdated",
        "keybindingsUpdated" -> JsonObject(current.orEmpty() + event.obj("payload"))
        "usageLimitSourcesUpdated" ->
            JsonObject(
                current.orEmpty() + ("usageLimitSources" to event.obj("payload").array("sources"))
            )
        "environmentThemesUpdated" ->
            JsonObject(
                current.orEmpty() + ("environmentThemes" to event.obj("payload").array("themes"))
            )
        else -> current
    }
