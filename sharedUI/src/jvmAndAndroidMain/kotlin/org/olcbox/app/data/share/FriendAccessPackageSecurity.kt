package org.olcbox.app.data.share

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object FriendAccessPackageSecurity {
    private const val PREFIX = "unifiedvpn-friend-v1"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MIN_PASSWORD_LENGTH = 12

    fun isEncryptedPackage(value: String): Boolean = value.trim().startsWith("$PREFIX:")

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(plainText: String, password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Package password must contain at least $MIN_PASSWORD_LENGTH characters"
        }
        require(plainText.isNotBlank()) { "Friend package is empty" }

        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(StandardCharsets.US_ASCII))
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        return listOf(
            PREFIX,
            Base64.UrlSafe.encode(salt),
            Base64.UrlSafe.encode(nonce),
            Base64.UrlSafe.encode(encrypted)
        ).joinToString(":")
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(envelope: String, password: String): String {
        require(password.isNotEmpty()) { "Enter the package password" }
        val parts = envelope.trim().split(':')
        require(parts.size == 4 && parts[0] == PREFIX) { "Unsupported friend package" }

        val salt = Base64.UrlSafe.decode(parts[1])
        val nonce = Base64.UrlSafe.decode(parts[2])
        val encrypted = Base64.UrlSafe.decode(parts[3])
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES && encrypted.isNotEmpty()) {
            "Friend package is damaged"
        }

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(StandardCharsets.US_ASCII))
        return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            passwordChars.fill('\u0000')
            spec.clearPassword()
        }
    }
}
