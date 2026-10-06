package com.v2ray.ang.handler

import android.util.Base64

/**
 * Public keys that sign release manifests (ECDSA P-256, X.509 SubjectPublicKeyInfo in Base64): the current key and a spare.
 * The private keys stay with the owner offline (OWNER-TODO.md). Empty until the owner makes the keys: then every update is
 * treated as unsigned and the user is asked before it is installed. The same list is in Windows Handler/UpdateKeys.cs.
 */
object UpdateKeys {
    val KEYS: List<String> = listOf(
    )

    fun publicKeys(): List<ByteArray> = KEYS.mapNotNull { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
}
