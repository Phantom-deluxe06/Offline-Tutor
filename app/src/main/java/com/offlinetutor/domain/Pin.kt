package com.offlinetutor.domain

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Optional profile PIN, stored as a salted PBKDF2-HMAC-SHA256 hash (never in plain text). */
object PinHasher {
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun verify(pin: String, salt: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())

    fun isValidPin(pin: String) = pin.length in 4..6 && pin.all { it.isDigit() }
}
