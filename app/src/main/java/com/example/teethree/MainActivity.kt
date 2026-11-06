package com.example.teethree

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.teethree.ui.T3App

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private var incomingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingLink =
            when {
                savedInstanceState != null -> savedInstanceState.getString("pending-link")
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> null
                intent.action == Intent.ACTION_VIEW -> intent.dataString
                else -> null
            }
        if (
            savedInstanceState == null &&
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        )
            stageShare(intent)
        setContent { T3App(model, incomingLink, onLinkConsumed = ::consumeIncomingLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink = if (intent.action == Intent.ACTION_VIEW) intent.dataString else null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) stageShare(intent)
    }

    private fun stageShare(intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        model.stageIncomingShare(intent)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    override fun onStart() {
        super.onStart()
        model.setForeground(true)
    }

    override fun onStop() {
        model.setForeground(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-link", incomingLink)
        super.onSaveInstanceState(outState)
    }

    private fun consumeIncomingLink() {
        incomingLink = null
        intent = Intent(intent).setData(null)
    }
}
