package com.example.teethree.data.auth

import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface DpopProofSigner {
    val thumbprint: String

    fun proof(method: String, url: String, accessToken: String? = null): String
}

class DpopSigner(
    private val keyPair: KeyPair,
    private val now: () -> Long = System::currentTimeMillis,
    private val identifier: () -> String = { UUID.randomUUID().toString() },
) : DpopProofSigner {
    private val publicJwk: JsonObject
    override val thumbprint: String

    init {
        val key = keyPair.public as? ECPublicKey ?: throw IOException("Invalid proof key.")
        require(key.params.curve.field.fieldSize == 256) { "Invalid proof key." }
        publicJwk = buildJsonObject {
            put("crv", "P-256")
            put("kty", "EC")
            put("x", base64Url(unsignedCoordinate(key.w.affineX)))
            put("y", base64Url(unsignedCoordinate(key.w.affineY)))
        }
        thumbprint = sha256Base64Url(publicJwk.toString())
    }

    override fun proof(method: String, url: String, accessToken: String?): String {
        val target = normalizeDpopUrl(url)
        require(method.matches(Regex("[A-Za-z]+"))) { "Invalid proof method." }
        val header = buildJsonObject {
            put("typ", "dpop+jwt")
            put("alg", "ES256")
            put("jwk", publicJwk)
        }
        val payload = buildJsonObject {
            put("htm", method.uppercase(Locale.ROOT))
            put("htu", target)
            put("iat", now() / 1_000)
            put("jti", identifier())
            accessToken?.let { put("ath", sha256Base64Url(it)) }
        }
        val input =
            "${base64Url(header.toString().toByteArray())}.${base64Url(payload.toString().toByteArray())}"
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(input.toByteArray(Charsets.US_ASCII))
                sign()
            }
        return "$input.${base64Url(ecdsaDerToJose(signature))}"
    }
}

internal fun normalizeDpopUrl(value: String): String {
    val url = value.toHttpUrlOrNull() ?: throw IOException("The authorization address is invalid.")
    require(url.username.isEmpty() && url.password.isEmpty()) { "Invalid authorization address." }
    return url.newBuilder().query(null).fragment(null).build().toString()
}

internal fun base64Url(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

internal fun sha256Base64Url(value: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

private fun unsignedCoordinate(value: BigInteger): ByteArray {
    val bytes =
        value.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.drop(1).toByteArray() else it
        }
    require(value.signum() >= 0 && bytes.size <= 32) { "Invalid proof key." }
    return ByteArray(32 - bytes.size) + bytes
}

internal fun ecdsaDerToJose(der: ByteArray): ByteArray {
    var cursor = 0
    fun byte(): Int {
        require(cursor < der.size) { "Invalid proof signature." }
        return der[cursor++].toInt() and 255
    }
    fun integer(): BigInteger {
        require(byte() == 2) { "Invalid proof signature." }
        val size = byte()
        require(size in 1..33 && cursor + size <= der.size) { "Invalid proof signature." }
        val bytes = der.copyOfRange(cursor, cursor + size)
        cursor += size
        require(
            bytes[0].toInt() >= 0 && (size == 1 || bytes[0] != 0.toByte() || bytes[1].toInt() < 0)
        ) {
            "Invalid proof signature."
        }
        return BigInteger(bytes)
    }
    require(byte() == 0x30 && byte() == der.size - 2) { "Invalid proof signature." }
    val r = integer()
    val s = integer()
    val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    require(cursor == der.size && r.signum() > 0 && r < order && s.signum() > 0 && s < order) {
        "Invalid proof signature."
    }
    // The shared verifier uses compact P-256 signatures; normalize S for its low-S policy.
    return unsignedCoordinate(r) + unsignedCoordinate(if (s > order.shiftRight(1)) order - s else s)
}
