package com.example.teethree.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable data class DirectConnectionRoute(val httpBaseUrl: String, val kind: String)

data class LearnedConnectionRoutes(
    val connectionId: String,
    val generation: Long,
    val environmentId: String,
    val routes: List<DirectConnectionRoute>,
    val cloudAccountId: String? = null,
)

internal val SavedConnection.effectiveHttpBaseUrl: String
    get() = selectedTransport?.httpBaseUrl ?: baseUrl
internal val SavedConnection.effectiveWsBaseUrl: String?
    get() =
        selectedTransport?.let { route ->
            route.httpBaseUrl
                .trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/ws"
        } ?: wsBaseUrl

internal fun connectionRouteId(route: DirectConnectionRoute?): String =
    route?.httpBaseUrl ?: "canonical"

private fun routeUrl(value: String): HttpUrl? =
    value.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }

internal fun directRouteKind(value: String, reportedKind: String? = null): String {
    val host = routeUrl(value)?.host?.lowercase()?.trimEnd('.') ?: return "public"
    val parts = host.split('.')
    val v4 =
        parts
            .takeIf {
                it.size == 4 &&
                    it.all { part -> part.toIntOrNull()?.let { n -> n in 0..255 } == true }
            }
            ?.map { it.toInt() }
    if (host == "localhost" || host == "::1" || v4?.first() == 127) return "loopback"
    if (reportedKind == "tailnet" || host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:"))
        return "tailnet"
    if (v4?.let { it[0] == 100 && it[1] in 64..127 } == true) return "vpn"
    if (
        host == "::" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host == "home.arpa" ||
            host.endsWith(".home.arpa") ||
            (!host.contains('.') && !host.contains(':'))
    )
        return "lan"
    if (
        v4?.let {
            it[0] in setOf(0, 10, 127) ||
                (it[0] == 172 && it[1] in 16..31) ||
                (it[0] == 192 && it[1] == 168) ||
                (it[0] == 169 && it[1] == 254) ||
                (it[0] == 198 && it[1] in 18..19)
        } == true
    )
        return "lan"
    val first = host.substringBefore(':').toIntOrNull(16)
    if (
        host.contains(':') &&
            first != null &&
            ((first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80)
    )
        return "lan"
    return "public"
}

private fun routeRank(kind: String): Int =
    when (kind) {
        "loopback" -> 0
        "lan" -> 1
        "tailnet",
        "vpn" -> 2
        "public" -> 3
        else -> 5
    }

internal fun mergeLearnedDirectRoutes(
    connection: SavedConnection,
    reported: JsonArray,
): List<DirectConnectionRoute> {
    if (connection.environmentId.isNullOrBlank()) return emptyList()
    val canonical = routeUrl(connection.baseUrl)
    val canonicalKind = directRouteKind(connection.baseUrl)
    val maySendBearerInClear =
        canonical?.scheme == "http" && canonicalKind in setOf("lan", "public")
    val accepted = linkedMapOf<String, DirectConnectionRoute>()
    reported.forEach { raw ->
        val value = raw as? JsonObject ?: return@forEach
        if (value.string("kind") !in setOf("lan", "tailnet")) return@forEach
        val url = routeUrl(value.string("httpBaseUrl")) ?: return@forEach
        val origin = url.newBuilder().encodedPath("/").build().toString()
        val kind = directRouteKind(origin, value.string("kind"))
        if (kind == "loopback") return@forEach
        if (canonical != null && origin.trimEnd('/') == canonical.toString().trimEnd('/'))
            return@forEach
        if (
            url.scheme == "http" &&
                connection.cloudAccountId == null &&
                kind != "tailnet" &&
                !maySendBearerInClear
        )
            return@forEach
        accepted[origin] = DirectConnectionRoute(origin, value.string("kind"))
    }
    val retained =
        connection.learnedRoutes.mapNotNull { accepted.remove(it.httpBaseUrl) }.toMutableList()
    accepted.values.forEach { route ->
        val rank = routeRank(directRouteKind(route.httpBaseUrl, route.kind))
        val position = retained.indexOfFirst {
            routeRank(directRouteKind(it.httpBaseUrl, it.kind)) > rank
        }
        if (position < 0) retained += route else retained.add(position, route)
    }
    return retained
}

internal fun orderedConnectionRoutes(connection: SavedConnection): List<DirectConnectionRoute?> {
    val valid =
        mergeLearnedDirectRoutes(
            connection,
            JsonArray(
                connection.learnedRoutes.map { route ->
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "httpBaseUrl",
                            kotlinx.serialization.json.JsonPrimitive(route.httpBaseUrl),
                        )
                        put("kind", kotlinx.serialization.json.JsonPrimitive(route.kind))
                    }
                }
            ),
        )
    val canonicalRank =
        if (connection.cloudAccountId != null) 5 else routeRank(directRouteKind(connection.baseUrl))
    val routes = valid.map<DirectConnectionRoute, DirectConnectionRoute?> { it }.toMutableList()
    val position = routes.indexOfFirst {
        routeRank(directRouteKind(it!!.httpBaseUrl, it.kind)) > canonicalRank
    }
    if (position < 0) routes += null else routes.add(position, null)
    return routes
}

internal data class ConnectionRouteAttempt(val transport: DirectConnectionRoute?)

internal class ConnectionRouteAttempts(
    scope: kotlinx.coroutines.CoroutineScope,
    private val routes: List<DirectConnectionRoute?>,
    probe: suspend (DirectConnectionRoute?) -> Boolean,
) : AutoCloseable {
    private val slots = Semaphore(4)
    private val checks =
        if (routes.size <= 1) emptyList()
        else routes.map { route -> scope.async { slots.withPermit { probe(route) } } }
    private val headStart = if (checks.isEmpty()) null else scope.async { delay(500) }
    private var position = 0
    private val late = mutableListOf<Int>()
    private var retries: MutableList<Int>? = null
    val hasMore: Boolean
        get() = position < routes.size || (retries?.isNotEmpty() ?: late.isNotEmpty())

    suspend fun next(): ConnectionRouteAttempt? {
        while (position < routes.size) {
            val index = position++
            val ready =
                if (checks.isEmpty()) true
                else
                    kotlinx.coroutines.selects.select {
                        checks[index].onAwait { it }
                        headStart!!.onAwait { false }
                    }
            if (ready) return ConnectionRouteAttempt(routes[index])
            late += index
        }
        if (retries == null) {
            val answered = late.filter { checks[it].await() }
            retries = (answered + late.filterNot { it in answered }).toMutableList()
        }
        val index = retries!!.removeFirstOrNull() ?: return null
        return ConnectionRouteAttempt(routes[index])
    }

    override fun close() {
        checks.forEach { it.cancel() }
        headStart?.cancel()
    }
}
