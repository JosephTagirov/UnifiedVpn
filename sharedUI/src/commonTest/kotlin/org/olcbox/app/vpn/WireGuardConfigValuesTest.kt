package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WireGuardConfigValuesTest {
    @Test
    fun parsesExplicitBooleanValuesWithoutChangingDefaults() {
        for (value in listOf("true", "1", "on", "yes", " TRUE ", "'true'")) {
            assertEquals(true, WireGuardConfigValues.boolean(value, "RandomTrailers"))
        }
        for (value in listOf("false", "0", "off", "no", " FALSE ", "\"false\"")) {
            assertEquals(false, WireGuardConfigValues.boolean(value, "DisableCookies"))
        }
    }

    @Test
    fun rejectsMalformedBooleansWithoutEchoingTheirContents() {
        for (value in listOf("", "2", "-1", "maybe", "true false", "true\nSECRET", "\"true'")) {
            val error = assertFailsWith<IllegalArgumentException> {
                WireGuardConfigValues.boolean(value, "RandomTrailers")
            }
            assertFalse(error.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun preservesZeroNumericAndRangedKeepaliveAsUnsigned32BitValues() {
        for ((input, expected) in mapOf("0" to "0", "025" to "25", "'22-30'" to "22-30",
            "25-25" to "25-25", "0-0" to "0-0", "4294967295" to "4294967295")) {
            assertEquals(expected, WireGuardConfigValues.keepalive(input))
        }
    }

    @Test
    fun rejectsReversedNegativeOverflowingOrMalformedKeepalive() {
        for (value in listOf("", "-1", "30-20", "25-", "25-30-35", "1.5", "+25", "25 - 30",
            "4294967296", "25-4294967296", "25-999999999999999999999", "SECRET")) {
            val error = assertFailsWith<IllegalArgumentException> { WireGuardConfigValues.keepalive(value) }
            assertFalse(error.message.orEmpty().contains("SECRET"))
        }
    }
}
