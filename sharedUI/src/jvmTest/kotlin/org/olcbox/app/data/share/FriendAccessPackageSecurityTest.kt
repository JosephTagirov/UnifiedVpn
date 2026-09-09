package org.olcbox.app.data.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendAccessPackageSecurityTest {
    @Test
    fun encryptedPackageDoesNotExposePlainText() {
        val plainText = "server-password-and-vless-link"
        val encrypted = FriendAccessPackageSecurity.encrypt(
            plainText = plainText,
            password = "correct horse battery staple"
        )

        assertTrue(FriendAccessPackageSecurity.isEncryptedPackage(encrypted))
        assertFalse(encrypted.contains(plainText))
        assertEquals(
            plainText,
            FriendAccessPackageSecurity.decrypt(encrypted, "correct horse battery staple")
        )
    }

    @Test
    fun wrongPasswordCannotDecryptPackage() {
        val encrypted = FriendAccessPackageSecurity.encrypt(
            plainText = "secret",
            password = "correct horse battery staple"
        )

        assertFailsWith<Exception> {
            FriendAccessPackageSecurity.decrypt(encrypted, "wrong password")
        }
    }

    @Test
    fun legacyCredentialEnvelopeIsRejectedFailClosed() {
        val legacyEnvelope = "unifiedvpn-friend-v1:AA:AA:AA"

        assertTrue(FriendAccessPackageSecurity.isEncryptedPackage(legacyEnvelope))
        val error = assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageSecurity.decrypt(legacyEnvelope, "correct horse battery staple")
        }
        assertTrue(error.message.orEmpty().contains("Legacy friend packages are rejected"))
    }

    @Test
    fun oversizedPlainTextAndEnvelopeAreRejectedBeforeCrypto() {
        val oversizedPlainText = "x".repeat(FriendAccessPackageCodec.MAX_PACKAGE_JSON_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageSecurity.encrypt(
                plainText = oversizedPlainText,
                password = "correct horse battery staple"
            )
        }

        val oversizedEnvelope = "unifiedvpn-friend-v2:" +
            "x".repeat(FriendAccessPackageSecurity.MAX_ENVELOPE_CHARS)
        assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageSecurity.decrypt(
                oversizedEnvelope,
                "correct horse battery staple"
            )
        }
    }
}
