package com.example.teethree

import com.example.teethree.data.CachedEnvironment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val offlineJson = Json { ignoreUnknownKeys = true }

internal fun ConnectionStore.offlineCache(connectionId: String): CachedEnvironment? =
    read("offline:$connectionId")?.let { offlineJson.decodeFromString(it) }

internal fun ConnectionStore.saveOfflineCache(
    connectionId: String,
    value: CachedEnvironment,
    isCurrent: () -> Boolean = { true },
) = writeIf("offline:$connectionId", offlineJson.encodeToString(value), isCurrent)

internal fun ConnectionStore.removeOfflineCache(connectionId: String) =
    remove("offline:$connectionId")
