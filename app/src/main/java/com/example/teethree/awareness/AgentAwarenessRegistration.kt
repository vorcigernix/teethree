package com.example.teethree.awareness

import android.content.Context
import com.example.teethree.data.auth.CloudAccountState
import com.example.teethree.data.auth.CloudIdentity
import com.example.teethree.data.auth.T3ConnectClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AgentAwarenessRegistration(
    context: Context,
    scope: CoroutineScope,
    private val account: StateFlow<CloudAccountState>,
    private val relay: T3ConnectClient?,
) : AutoCloseable {
    private val store = AwarenessStore(context)
    private val app = context.applicationContext
    private val refreshes = MutableStateFlow(0L)
    private val mutableStatus = MutableStateFlow("Sign into T3 Connect to register this device.")
    val status = mutableStatus.asStateFlow()
    private var signingOut: CloudIdentity? = null

    private val job = scope.launch {
        combine(account, refreshes, AgentPushTokens.changes) { state, _, _ -> state }
            .collectLatest { state ->
                val identity = state.identity
                if (identity == null) {
                    signingOut = null
                    store.signOut()
                    mutableStatus.value = "Sign into T3 Connect to register this device."
                    return@collectLatest
                }
                if (identity == signingOut) return@collectLatest
                store.activate(identity.accountId)
                if (relay == null) {
                    mutableStatus.value = "T3 Connect is not configured for this build."
                    return@collectLatest
                }
                try {
                    val payload = store.registration()
                    if (account.value.identity != identity || identity == signingOut)
                        return@collectLatest
                    relay.request("POST", "/v1/mobile/devices", payload)
                    if (account.value.identity == identity && identity != signingOut) {
                        mutableStatus.value =
                            if (AgentPushTokens.configured(app))
                                "This device is registered for agent updates."
                            else
                                "Device registered; Firebase push is not configured for this build."
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (account.value.identity == identity && identity != signingOut)
                        mutableStatus.value =
                            "Device registration failed. Open teethree or retry after reconnecting."
                }
            }
    }

    fun refresh() {
        refreshes.value += 1L
    }

    suspend fun beforeSignOut() {
        signingOut = account.value.identity
        store.signOut()
        refresh()
        try {
            if (signingOut != null) relay?.request("DELETE", "/v1/mobile/devices/${store.deviceId}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableStatus.value =
                "Local agent updates are disabled. The remote device could not be removed."
        }
    }

    override fun close() {
        job.cancel()
    }
}
