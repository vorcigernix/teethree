package com.example.teethree.data.auth

import android.app.Application
import com.example.teethree.BuildConfig
import com.example.teethree.ConnectionStore
import com.example.teethree.data.SavedConnection
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class NativeCloudConnections(
    application: Application,
    scope: CoroutineScope,
    store: ConnectionStore,
    client: OkHttpClient = OkHttpClient(),
    beforeHttpAccess: suspend (String) -> Unit = {},
) {
    private val configuration = runCatching {
        CloudConfig.parse(
            BuildConfig.T3CODE_CLERK_PUBLISHABLE_KEY,
            BuildConfig.T3CODE_CLERK_JWT_TEMPLATE,
            BuildConfig.T3CODE_RELAY_URL,
        )
    }
    val config = configuration.getOrNull()
    val configurationError =
        if (configuration.isFailure) "T3 Connect is not configured correctly in this build."
        else null
    val account = ClerkCloudAccount(application, scope, config)
    val relay = config?.let {
        T3ConnectClient(
            it,
            account,
            androidDpopSigner(),
            scope,
            client,
            loadTokens = { store.cloudTokens() },
            saveTokens = { tokens -> store.saveCloudTokens(tokens) },
        )
    }
    val authorization = relay?.let { EnvironmentAuthorization(it, beforeHttpAccess) }
    var beforeSignOut: suspend () -> Unit = {}

    init {
        scope.launch {
            account.state
                .map { it.identity }
                .distinctUntilChanged()
                .collect { authorization?.clear() }
        }
    }

    suspend fun connect(environment: CloudEnvironment): SavedConnection {
        val identity =
            account.state.value.identity ?: throw IOException("Sign in to T3 Connect to continue.")
        val candidate =
            SavedConnection(
                id = "relay:${identity.accountId}:${environment.environmentId}",
                baseUrl = environment.endpoint.httpBaseUrl,
                accessToken = "",
                scopes = emptySet(),
                expiresAt = 0,
                tokenType = "DPoP",
                wsBaseUrl = environment.endpoint.wsBaseUrl,
                environmentId = environment.environmentId,
                cloudAccountId = identity.accountId,
                label = environment.label,
                connectionMethod = "relay",
            )
        return authorization?.prepare(candidate)
            ?: throw IOException("T3 Connect is not configured.")
    }

    suspend fun unlink(environmentId: String) {
        relay?.unlink(environmentId) ?: throw IOException("T3 Connect is not configured.")
        authorization?.clear()
    }

    suspend fun signOut() {
        try {
            beforeSignOut()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
        try {
            account.signOut()
        } finally {
            authorization?.clear()
            relay?.clear()
        }
    }
}
