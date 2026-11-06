package com.example.teethree.data.auth

import android.content.Context
import android.net.Uri
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.clerk.api.session.Session
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ClerkCloudAccount(context: Context, scope: CoroutineScope, val config: CloudConfig?) :
    CloudSessionSource {
    private val mutableState =
        MutableStateFlow(
            CloudAccountState(configured = config != null, initialized = config == null)
        )
    override val state = mutableState.asStateFlow()

    init {
        if (config != null) {
            synchronized(ClerkCloudAccount::class.java) {
                if (configuredKey == null) {
                    Clerk.initialize(
                        context.applicationContext,
                        config.publishableKey,
                        options = ClerkConfigurationOptions(telemetryEnabled = false),
                    )
                    configuredKey = config.publishableKey
                } else
                    check(configuredKey == config.publishableKey) {
                        "T3 Connect configuration changed. Restart this app."
                    }
            }
            scope.launch {
                combine(Clerk.isInitialized, Clerk.sessionFlow, Clerk.initializationError) {
                        initialized,
                        session,
                        error ->
                        val identity =
                            session
                                ?.takeIf {
                                    initialized && it.status == Session.SessionStatus.ACTIVE
                                }
                                ?.let { active ->
                                    (active.user?.id ?: Clerk.activeUser?.id)?.let {
                                        CloudIdentity(it, active.id)
                                    }
                                }
                        CloudAccountState(
                            true,
                            initialized,
                            identity,
                            session?.status == Session.SessionStatus.PENDING,
                            if (error != null && !initialized)
                                "T3 Connect sign-in could not be loaded. Check your connection and retry."
                            else null,
                        )
                    }
                    .collect { mutableState.value = it }
            }
        }
    }

    override suspend fun token(): String {
        val settings = config ?: throw IOException("T3 Connect is not configured in this build.")
        val expected = state.value.identity ?: throw IOException("Finish signing in to T3 Connect.")
        return when (
            val result =
                Clerk.auth.getToken(
                    GetTokenOptions(template = settings.jwtTemplate, skipCache = true)
                )
        ) {
            is ClerkResult.Success -> {
                if (
                    state.value.identity != expected ||
                        Clerk.activeSession?.id != expected.sessionId
                )
                    throw IOException("The T3 Connect account changed. Try again.")
                result.value.takeIf(String::isNotBlank)
                    ?: throw IOException("T3 Connect did not return a sign-in token.")
            }
            is ClerkResult.Failure ->
                throw IOException("T3 Connect sign-in could not be refreshed. Sign in again.")
        }
    }

    override suspend fun signOut() {
        if (config == null) return
        when (Clerk.auth.signOut()) {
            is ClerkResult.Success ->
                mutableState.value = CloudAccountState(configured = true, initialized = true)
            is ClerkResult.Failure ->
                throw IOException("T3 Connect could not sign out. Check your connection and retry.")
        }
    }

    suspend fun handle(uri: Uri?): Boolean =
        if (config == null || uri == null || !uri.isHierarchical) false else Clerk.auth.handle(uri)

    fun retry() {
        if (config != null) Clerk.reinitialize()
    }

    companion object {
        private var configuredKey: String? = null
    }
}
