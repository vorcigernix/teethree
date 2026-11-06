package com.example.teethree

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClientPreferences(
    val followUpBehavior: String = "queue",
    val projectGrouping: String = "repository",
    val composerEnterBehavior: String = "send",
    /** "voice" leads with the voice dock; "keyboard" leads with the text field. */
    val composerInput: String = "voice",
    val appearance: ClientAppearancePreferences = ClientAppearancePreferences(),
)

internal fun ConnectionStore.clientPreferences(): ClientPreferences =
    read("client-preferences")?.let { Json.decodeFromString<ClientPreferences>(it) }
        ?: ClientPreferences()

internal fun ConnectionStore.saveClientPreferences(value: ClientPreferences) =
    write("client-preferences", Json.encodeToString(value))
