package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationProfileSwitcherTest {
    @Test
    fun movesInStoredOrderAndWrapsAtBothEnds() {
        val profiles = listOf("rtc", "vless", "awg")

        assertEquals("vless", notificationProfileTargetId(profiles, "rtc", 1))
        assertEquals("awg", notificationProfileTargetId(profiles, "rtc", -1))
        assertEquals("rtc", notificationProfileTargetId(profiles, "awg", 1))
    }

    @Test
    fun coalescesMultipleButtonPressesIntoOneTarget() {
        val profiles = listOf("rtc", "vless", "awg")

        assertEquals("awg", notificationProfileTargetId(profiles, "rtc", 2))
        assertEquals("vless", notificationProfileTargetId(profiles, "rtc", -2))
        assertNull(notificationProfileTargetId(profiles, "rtc", 3))
    }

    @Test
    fun ignoresBlankDuplicateAndUnusableInputs() {
        assertEquals(
            "vless",
            notificationProfileTargetId(listOf("rtc", "", "rtc", "vless"), "rtc", 1)
        )
        assertNull(notificationProfileTargetId(emptyList(), null, 1))
        assertNull(notificationProfileTargetId(listOf("rtc"), "rtc", 1))
        assertNull(notificationProfileTargetId(listOf("rtc", "vless"), "rtc", 0))
    }

    @Test
    fun acceptsOnlyAStoredExplicitSelectionDifferentFromTheRunningProfile() {
        val profiles = listOf("rtc", "vless", "awg")

        assertEquals("awg", notificationSelectedProfileTargetId(profiles, "rtc", "awg"))
        assertNull(notificationSelectedProfileTargetId(profiles, "rtc", "rtc"))
        assertNull(notificationSelectedProfileTargetId(profiles, "rtc", "unknown"))
        assertNull(notificationSelectedProfileTargetId(profiles, "rtc", null))
    }

    @Test
    fun displayNameRemovesControlAndDirectionCharactersAndLimitsLength() {
        val longName = "  Main\nprofile\u202E\u0000" + "x".repeat(100)
        val sanitized = notificationSafeProfileName(longName)

        assertEquals("Main profile " + "x".repeat(67), sanitized)
        assertEquals(80, sanitized.length)
        assertEquals("Profile", notificationSafeProfileName("\u0000\n"))
    }
}
