package com.example.teethree

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.teethree.data.ConnectionAccessDeniedException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ensureLocalNetworkAccess(context: Context, origin: String) {
    if (Build.VERSION.SDK_INT < 37 || localNetworkPermissionGranted(context)) return
    withContext(Dispatchers.IO) {
        val host = requireNotNull(URI(origin).host).trim('[', ']')
        val localName = requiresLocalNetworkAccess(host, emptyList())
        val addresses =
            if (localName) emptyList()
            else
                try {
                    InetAddress.getAllByName(host).toList()
                } catch (_: UnknownHostException) {
                    emptyList()
                }
        if (localName || requiresLocalNetworkAccess(host, addresses)) {
            throw ConnectionAccessDeniedException(
                "Allow local network access in Settings to connect to this environment."
            )
        }
    }
}

internal fun localNetworkPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

internal fun requiresLocalNetworkAccess(host: String, addresses: List<InetAddress>): Boolean {
    val name = host.lowercase().trimEnd('.')
    if (
        name == "localhost" ||
            name.endsWith(".localhost") ||
            name.endsWith(".local") ||
            name.endsWith(".ts.net")
    )
        return true
    return addresses.any { address ->
        val bytes = address.address
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            (bytes.size == 4 &&
                bytes[0].toInt() and 255 == 100 &&
                bytes[1].toInt() and 255 in 64..127) ||
            (bytes.size == 16 && bytes[0].toInt() and 254 == 252)
    }
}

/**
 * True when a typed server address or pairing link points at this network, judged without a DNS
 * lookup so it is safe on the main thread: local names and private or loopback IP literals.
 */
internal fun looksLikeLocalNetwork(input: String): Boolean {
    val host =
        runCatching { com.example.teethree.data.parsePairingTarget(input, "-").first }
            .mapCatching { URI(it).host?.trim('[', ']') }
            .getOrNull() ?: return false
    val literal = host.matches(Regex("[0-9.]+")) || host.contains(':')
    val addresses =
        if (literal) runCatching { listOf(InetAddress.getByName(host)) }.getOrDefault(emptyList())
        else emptyList()
    return requiresLocalNetworkAccess(host, addresses)
}
