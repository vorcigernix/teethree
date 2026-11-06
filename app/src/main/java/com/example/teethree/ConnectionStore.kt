package com.example.teethree

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.SavedConnection
import com.example.teethree.ui.AppTheme
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("native-client", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val keyAlias = "t3-native-connections"

    private fun key(): SecretKey {
        synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(keyAlias, null) as? SecretKey)?.let {
                return it
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        }
    }

    private companion object {
        val keyLock = Any()
    }

    internal fun read(name: String): String? {
        val stored = preferences.getString(name, null) ?: return null
        val parts = stored.split(":", limit = 2)
        require(parts.size == 2) {
            "Saved credentials could not be read. Pair this environment again."
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }

    internal fun write(name: String, value: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit()) { "Could not save app data." }
    }

    fun connections(): List<SavedConnection> =
        read("connections")?.let { json.decodeFromString(it) } ?: emptyList()

    fun saveConnections(connections: List<SavedConnection>) =
        write("connections", json.encodeToString(connections))

    fun drafts(): Map<String, ComposerDraft> =
        read("drafts")?.let { json.decodeFromString(it) } ?: emptyMap()

    fun saveDrafts(drafts: Map<String, ComposerDraft>) =
        write("drafts", json.encodeToString(drafts))

    /** Messages written while the environment was unreachable, keyed like drafts. */
    fun outbox(): Map<String, List<ComposerDraft>> =
        read("outbox")?.let { json.decodeFromString(it) } ?: emptyMap()

    fun saveOutbox(outbox: Map<String, List<ComposerDraft>>) =
        write("outbox", json.encodeToString(outbox))

    internal fun writeIf(name: String, value: String, isCurrent: () -> Boolean): Boolean =
        synchronized(keyLock) {
            if (!isCurrent()) false
            else {
                write(name, value)
                true
            }
        }

    internal fun remove(name: String) =
        synchronized(keyLock) {
            check(preferences.edit().remove(name).commit()) { "Could not remove saved app data." }
        }

    internal fun removePrefix(prefix: String) =
        synchronized(keyLock) {
            val editor = preferences.edit()
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
            check(editor.commit()) { "Could not remove saved app data." }
        }

    internal fun offlineCacheBytes(): Long =
        preferences.all
            .filterKeys { it.startsWith("offline:") }
            .values
            .sumOf { (it as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }

    var activeConnectionId: String?
        get() = preferences.getString("activeConnectionId", null)
        set(value) {
            preferences.edit().putString("activeConnectionId", value).apply()
        }

    var theme: AppTheme
        get() =
            preferences.getString("theme", null)?.let { value ->
                AppTheme.entries.find { it.name == value }
            } ?: AppTheme.System
        set(value) {
            preferences.edit().putString("theme", value.name).apply()
        }
}
