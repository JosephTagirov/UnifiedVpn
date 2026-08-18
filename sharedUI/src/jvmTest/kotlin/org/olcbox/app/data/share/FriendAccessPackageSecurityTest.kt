package org.olcbox.app.data.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

        assertFails {
            FriendAccessPackageSecurity.decrypt(encrypted, "wrong password")
        }
    }
}
