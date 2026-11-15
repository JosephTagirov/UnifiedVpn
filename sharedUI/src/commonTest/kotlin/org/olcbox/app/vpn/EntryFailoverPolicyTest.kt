package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EntryFailoverPolicyTest {
    @Test fun startupUsesAWorkingBackupWithoutWaitingForAnotherFailedPrimaryProbe() {
        val policy = EntryFailoverPolicy(0)
        assertEquals(VpnEntryChoice.BACKUP, policy.observe(false, true, 6_000))
        // A health result alone does not claim that the native route was selected.
        assertEquals(VpnEntryChoice.PRIMARY, policy.selected)
        policy.switchedTo(VpnEntryChoice.BACKUP, 6_100)
        assertEquals(VpnEntryChoice.BACKUP, policy.selected)
    }

    @Test fun startupPrefersPrimaryAndNeverSelectsAnUnverifiedEntry() {
        val policy = EntryFailoverPolicy(0)
        assertNull(policy.observe(false, false, 6_000))
        assertNull(policy.observe(false, false, 22_000))
        assertEquals(VpnEntryChoice.PRIMARY, policy.observe(true, true, 32_000))
    }

    @Test fun failedInitialApiAcknowledgementIsRetriedWithoutStartingTheBackupHoldDown() {
        val policy = EntryFailoverPolicy(0)
        assertEquals(VpnEntryChoice.BACKUP, policy.observe(false, true, 6_000))
        // The first API write/readback failed: switchedTo must not be called.
        assertEquals(VpnEntryChoice.PRIMARY, policy.observe(true, true, 16_000))
        policy.switchedTo(VpnEntryChoice.PRIMARY, 16_100)
        assertNull(policy.observe(false, true, 26_000))
        assertEquals(VpnEntryChoice.BACKUP, policy.observe(false, true, 36_000))
    }

    @Test fun aPreviouslyAcknowledgedBlockedSessionKeepsEstablishedHysteresis() {
        val policy = EntryFailoverPolicy(0)
        val route = EntryRouteAcknowledgement()
        policy.switchedTo(VpnEntryChoice.PRIMARY, 0)
        route.selected(VpnEntryChoice.PRIMARY, true)
        assertNull(policy.observe(false, false, 10_000))
        assertNull(policy.observe(true, false, 20_000))
        route.blocked(true)
        assertNull(route.entry)
        assertNull(policy.observe(false, true, 30_000))
        assertEquals(VpnEntryChoice.BACKUP, policy.observe(false, true, 40_000))
    }

    @Test fun uncertainBackupWriteCannotLeaveAPrimaryRecoveryUnreconciled() {
        val route = EntryRouteAcknowledgement()
        route.selected(VpnEntryChoice.PRIMARY, true)
        // The native write may select backup while its readback times out.
        route.selected(VpnEntryChoice.BACKUP, false)
        assertNull(route.entry)
        assertTrue(route.needsSelection(VpnEntryChoice.PRIMARY))
        route.selected(VpnEntryChoice.PRIMARY, true)
        assertFalse(route.needsSelection(VpnEntryChoice.PRIMARY))
    }

    @Test fun uncertainBlockMustBeRetriedUntilAcknowledged() {
        val route = EntryRouteAcknowledgement()
        route.selected(VpnEntryChoice.BACKUP, true)
        route.blocked(false)
        assertTrue(route.needsBlock())
        route.blocked(true)
        assertFalse(route.needsBlock())
    }

    @Test fun outageNeedsTwoFailuresAndAWorkingBackup() {
        val policy = EntryFailoverPolicy(0)
        policy.switchedTo(VpnEntryChoice.PRIMARY, 0)
        assertNull(policy.observe(false, true, 0))
        assertNull(policy.observe(false, false, 5_000))
        assertEquals(VpnEntryChoice.BACKUP, policy.observe(false, true, 10_000))
        // An API failure leaves the currently selected route unchanged.
        assertEquals(VpnEntryChoice.PRIMARY, policy.selected)
        policy.switchedTo(VpnEntryChoice.BACKUP, 10_000)
        assertEquals(VpnEntryChoice.BACKUP, policy.selected)
    }

    @Test fun recoveryNeedsConsecutiveSuccessesAndHoldDown() {
        val policy = EntryFailoverPolicy(0)
        policy.switchedTo(VpnEntryChoice.BACKUP, 10_000)
        assertNull(policy.observe(true, true, 15_000))
        assertNull(policy.observe(true, true, 20_000))
        assertNull(policy.observe(true, true, 25_000))
        assertNull(policy.observe(false, true, 40_000))
        assertNull(policy.observe(true, true, 45_000))
        assertNull(policy.observe(true, true, 50_000))
        assertEquals(VpnEntryChoice.PRIMARY, policy.observe(true, true, 55_000))
    }

    @Test fun intermittentFailureDoesNotMovePrimaryOrFlapDuringTotalOutage() {
        val policy = EntryFailoverPolicy(0)
        policy.switchedTo(VpnEntryChoice.PRIMARY, 0)
        repeat(5) {
            assertNull(policy.observe(false, true, it * 10_000L))
            assertNull(policy.observe(true, true, it * 10_000L + 5_000))
        }
        repeat(5) { assertNull(policy.observe(false, false, 60_000L + it * 5_000)) }
        assertEquals(VpnEntryChoice.PRIMARY, policy.selected)
    }
}
