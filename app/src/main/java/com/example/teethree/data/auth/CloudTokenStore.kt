package com.example.teethree.data.auth

import com.example.teethree.ConnectionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal suspend fun ConnectionStore.cloudTokens(): List<RelayAccessToken> =
    withContext(Dispatchers.IO) {
        try {
            read("connect-relay-tokens")?.let { Json.decodeFromString<List<RelayAccessToken>>(it) }
                ?: emptyList()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IOException(
                "Saved T3 Connect credentials could not be opened. Sign out and sign in again."
            )
        }
    }

internal suspend fun ConnectionStore.saveCloudTokens(tokens: List<RelayAccessToken>) =
    withContext(Dispatchers.IO) {
        write("connect-relay-tokens", Json.encodeToString(tokens))
    }
