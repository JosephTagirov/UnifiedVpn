package org.olcbox.app.vpn.desktop

import kotlin.test.*
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.vpn.ManagedEntryDns

class DesktopEntryRoutingTest {
    private fun profile(host: String = "ru-kvn.meesharr.space") = VpnProfileConfig(type = "vless", uri =
        "vless://00000000-0000-4000-8000-000000000000@$host:443?type=ws&security=tls&sni=ru-kvn.meesharr.space&host=ru-kvn.meesharr.space&path=%2Fsynthetic")

    @Test fun bothActualEntriesBypassCaptureEvenWhenPublicPrimaryDnsPointsAtTheBackup() {
        val source = profile()
        val plan = DesktopEntryRouting.plan(source, null, listOf("ru-kvn.meesharr.space"),
            DesktopOs.Windows, probeOnly = false, separateDownloadEndpoint = false)
        assertTrue(plan.applyDirectRouting)
        val entries = assertNotNull(plan.entries)
        assertEquals(ManagedEntryDns.PRIMARY_HOST, entries.primary.host)
        assertEquals("48.103.218.29", entries.backup.host)
        assertEquals(listOf(entries.primary.host, entries.backup.host), plan.bypassHosts)
        assertFalse("ru-kvn.meesharr.space" in plan.bypassHosts,
            "A healthy literal backup must not require resolving the saved TLS identity hostname")
        assertEquals(profile().uri, source.uri)
    }

    @Test fun aLatencyProbeUsesOnlyItsChosenProfileWithoutPolicyOrAnExtraAwgIdentityOwner() {
        val plan = DesktopEntryRouting.plan(profile(), null, listOf("ru-kvn.meesharr.space"),
            DesktopOs.Windows, probeOnly = true, separateDownloadEndpoint = false)
        assertFalse(plan.applyDirectRouting)
        assertNull(plan.entries)
        assertEquals(listOf("ru-kvn.meesharr.space"), plan.bypassHosts)
    }

    @Test fun thirdPartyProfilesGetServiceRoutingWithoutInventingManagedBackupCredentials() {
        val plan = DesktopEntryRouting.plan(profile("third-party.example"), null, listOf("third-party.example"),
            DesktopOs.Windows, probeOnly = false, separateDownloadEndpoint = false)
        assertTrue(plan.applyDirectRouting)
        assertNull(plan.entries)
        assertEquals(listOf("third-party.example"), plan.bypassHosts)
    }

    @Test fun aSeparateDownloadEndpointAndOtherOperatingSystemsKeepTheirOriginalDialPlan() {
        val hosts = listOf("ru-kvn.meesharr.space", "download.example")
        val split = DesktopEntryRouting.plan(profile(), null, hosts, DesktopOs.Windows,
            probeOnly = false, separateDownloadEndpoint = true)
        assertTrue(split.applyDirectRouting)
        assertNull(split.entries)
        assertEquals(hosts, split.bypassHosts)
        val other = DesktopEntryRouting.plan(profile(), null, hosts, DesktopOs.Linux,
            probeOnly = false, separateDownloadEndpoint = false)
        assertFalse(other.applyDirectRouting)
        assertNull(other.entries)
        assertEquals(hosts, other.bypassHosts)
    }
}
