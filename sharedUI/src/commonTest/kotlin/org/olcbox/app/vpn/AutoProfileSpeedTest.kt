package org.olcbox.app.vpn

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoProfileSpeedTest {
    @Test
    fun onlyMeasuredAndMeaningfullyFasterCandidatesWin() {
        assertFalse(AutoProfileSpeed.isFaster(null, 200))
        assertFalse(AutoProfileSpeed.isFaster(100, null))
        assertFalse(AutoProfileSpeed.isFaster(0, 200))
        assertFalse(AutoProfileSpeed.isFaster(100, 124))
        assertTrue(AutoProfileSpeed.isFaster(100, 125))
    }

    @Test
    fun cyclesThroughEligibleCandidatesWhileAvoidingASecondSessionInTheActiveRoom() {
        val current = rtc("active", "room-one")
        val sameRoom = rtc("alias", "room-one", key = "c")
        val legacy = rtc("legacy", "room-three", LocationConfig.PROVIDER_JITSI)
        val duplicateKey = rtc("duplicate-key", "room-copy", key = "A")
        val otherRoom = rtc("candidate", "room-two", key = "b")
        val vless = LocationEntry.fromProfile(
            "vless",
            VpnProfileConfig(
                type = VpnProfileConfig.TYPE_VLESS,
                uri = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443"
            )
        )
        val profiles = listOf(vless, current, sameRoom, duplicateKey, legacy, otherRoom)

        assertNull(AutoProfileSpeed.nextCandidate(current, listOf(current, sameRoom, legacy), null))
        assertEquals(vless.storageId, AutoProfileSpeed.nextCandidate(current, profiles, null)?.storageId)
        assertEquals(otherRoom.storageId, AutoProfileSpeed.nextCandidate(current, profiles, vless.storageId)?.storageId)
        assertEquals(vless.storageId, AutoProfileSpeed.nextCandidate(current, profiles, otherRoom.storageId)?.storageId)
    }

    @Test
    fun oneGoodSampleDoesNotJustifySwitching() = runTest {
        val candidate = listOf(200L, 120L).iterator()
        assertNull(AutoProfileSpeed.confirmImprovement({ 100 }, { candidate.next() }, { true }))
        assertFalse(candidate.hasNext())
    }

    @Test
    fun twoConsistentlyFasterSamplesConfirmImprovement() = runTest {
        val candidate = listOf(200L, 180L).iterator()
        assertEquals(
            AutoProfileSpeed.Measurement(100, 180),
            AutoProfileSpeed.confirmImprovement({ 100 }, { candidate.next() }, { true })
        )
    }

    @Test
    fun changedConnectionInvalidatesAnOtherwiseFastResult() = runTest {
        var sameConnection = true
        assertNull(AutoProfileSpeed.confirmImprovement(
            measureCurrent = { 100 },
            measureCandidate = { sameConnection = false; 200 },
            sameConnection = { sameConnection }
        ))
    }

    private fun rtc(
        id: String,
        room: String,
        provider: String = LocationConfig.PROVIDER_VK_CALLS,
        key: String = "a"
    ): LocationEntry = LocationEntry.from(id, LocationConfig(id = room, key = key.repeat(64), bypassProvider = provider))
}
