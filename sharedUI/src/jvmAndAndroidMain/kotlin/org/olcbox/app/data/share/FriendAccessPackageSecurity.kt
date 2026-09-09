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
    private const val PREFIX = "unifiedvpn-friend-v2"
    private const val LEGACY_PREFIX = "unifiedvpn-friend-v1"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MIN_PASSWORD_LENGTH = 12
    private const val MAX_PASSWORD_LENGTH = 256
    private const val MAX_PLAINTEXT_BYTES = FriendAccessPackageCodec.MAX_PACKAGE_JSON_BYTES
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES
    internal const val MAX_ENVELOPE_CHARS = 350_000

    fun isEncryptedPackage(value: String): Boolean {
        val start = value.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return false
        return value.regionMatches(start, "$PREFIX:", 0, PREFIX.length + 1) ||
            value.regionMatches(start, "$LEGACY_PREFIX:", 0, LEGACY_PREFIX.length + 1)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(plainText: String, password: String): String {
        require(password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Package password must contain at least $MIN_PASSWORD_LENGTH characters"
        }
        require(plainText.isNotBlank()) { "Friend package is empty" }
        val plainBytes = plainText.toByteArray(StandardCharsets.UTF_8)
        require(plainBytes.size <= MAX_PLAINTEXT_BYTES) { "Friend package is too large" }

        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(StandardCharsets.US_ASCII))
        val encrypted = try {
            cipher.doFinal(plainBytes)
        } finally {
            plainBytes.fill(0)
        }

        return listOf(
            PREFIX,
            Base64.UrlSafe.encode(salt),
            Base64.UrlSafe.encode(nonce),
            Base64.UrlSafe.encode(encrypted)
        ).joinToString(":").also {
            require(it.length <= MAX_ENVELOPE_CHARS) { "Encrypted friend package is too large" }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(envelope: String, password: String): String {
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) {
            "Enter a valid package password"
        }
        require(envelope.length <= MAX_ENVELOPE_CHARS) { "Friend package is too large" }
        val trimmed = envelope.trim()
        require(!trimmed.startsWith("$LEGACY_PREFIX:")) {
            "Legacy friend packages are rejected because they may contain SSH credentials"
        }
        val parts = trimmed.split(':', limit = 5)
        require(parts.size == 4 && parts[0] == PREFIX) { "Unsupported friend package" }
        require(parts[1].length <= 32 && parts[2].length <= 24 && parts[3].length <= 349_552) {
            "Friend package is damaged"
        }

        val salt = Base64.UrlSafe.decode(parts[1])
        val nonce = Base64.UrlSafe.decode(parts[2])
        val encrypted = Base64.UrlSafe.decode(parts[3])
        require(
            salt.size == SALT_BYTES &&
                nonce.size == NONCE_BYTES &&
                encrypted.size in (TAG_BYTES + 1)..MAX_CIPHERTEXT_BYTES
        ) {
            "Friend package is damaged"
        }

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(StandardCharsets.US_ASCII))
        val plainBytes = cipher.doFinal(encrypted)
        require(plainBytes.size <= MAX_PLAINTEXT_BYTES) { "Friend package is too large" }
        return try {
            plainBytes.toString(StandardCharsets.UTF_8)
        } finally {
            plainBytes.fill(0)
        }
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
