package com.example.teethree.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

internal fun androidDpopSigner(): DpopProofSigner {
    val alias = "teethree-connect-dpop-p256"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val privateKey = store.getKey(alias, null) as? PrivateKey
    val pair =
        if (privateKey != null) KeyPair(store.getCertificate(alias).publicKey, privateKey)
        else
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generateKeyPair()
            }
    return DpopSigner(pair)
}
