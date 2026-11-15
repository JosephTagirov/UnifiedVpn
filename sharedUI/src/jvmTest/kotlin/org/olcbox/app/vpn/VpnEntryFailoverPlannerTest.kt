package org.olcbox.app.vpn

import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import kotlinx.serialization.json.*
import kotlin.test.*

class VpnEntryFailoverPlannerTest {
    @Test fun deployedProfilesGainFailoverWithoutChangingTheirSavedUri() {
        val profile = VpnProfileConfig(type = "vless", uri = uri("ru-kvn.meesharr.space"))
        val pair = assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        assertEquals(ManagedEntryDns.PRIMARY_HOST, pair.primary.host)
        assertEquals("48.103.218.29", pair.backup.host)
        assertEquals(uri("ru-kvn.meesharr.space"), profile.uri)
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(uri = uri("jh-kvn.meesharr.space"))))
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(uri = uri("other.example"))))
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(localSocksPort = 1080)))
    }

    @Test fun temporaryVlessEndpointRetainsVerifiedTlsAndTransportFields() {
        val source = uri("ru-kvn.meesharr.space")
        val profile = VpnProfileConfig(type = "vless", uri = source, rawConfig = source)
        val effective = VpnEntryFailoverPlanner.forEndpoint(profile, VpnEntryEndpoint("2001:db8::2", 8443))
        assertEquals(source.substringBefore('@') + "@[2001:db8::2]:8443?" + source.substringAfter('?'), effective.uri)
        assertEquals(effective.uri, effective.rawConfig)
        assertEquals(source, profile.uri)
        assertNull(effective.entryFailover)
    }

    @Test fun awgRetainsKeysAddressesAndObfuscationWhenOnlyEndpointChanges() {
        val raw = "[Interface]\nPrivateKey = private-placeholder\nAddress = 10.0.0.2/32\nJc = 5\n[Peer]\nPublicKey = public-placeholder\nEndpoint = awg-legacy-primary.meesharr.space:18443\nAllowedIPs = 0.0.0.0/0\n"
        val profile = VpnProfileConfig(type = "awg", rawConfig = raw)
        assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        val changed = VpnEntryFailoverPlanner.forEndpoint(profile, VpnEntryEndpoint("192.0.2.7", 18443)).rawConfig
        assertEquals(raw.replace("awg-legacy-primary.meesharr.space:18443", "192.0.2.7:18443"), changed)
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(rawConfig = raw + "[Peer]\nEndpoint = other.example:18443\n")))
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(rawConfig = raw.replace(":18443", ":51820"))))
    }

    @Test fun suppliedPairMustStartAtTheOriginalDialTarget() {
        val profile = VpnProfileConfig(type = "vless", uri = uri("third-party.example"), entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("third-party.example", 443), VpnEntryEndpoint("backup.example", 443)))
        assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        assertNull(VpnEntryFailoverPlanner.plan(profile.copy(entryFailover = profile.entryFailover!!.copy(primary = VpnEntryEndpoint("wrong.example", 443)))))
    }

    @Test fun knownSuppliedMetadataStillChecksTheRealPrimaryWhilePublicAliasesUseBackupDns() {
        val source = uri("ru-kvn.meesharr.space")
        val profile = VpnProfileConfig(type = "vless", uri = source, entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("ru-kvn.meesharr.space", 443), VpnEntryEndpoint("jh-kvn.meesharr.space", 443)))
        val pair = assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        assertEquals(ManagedEntryDns.PRIMARY_HOST, pair.primary.host)
        assertEquals("48.103.218.29", pair.backup.host)
        val socks = XraySocksSettings(port = 14080, username = "synthetic-user", password = "synthetic-password")
        val original = Json.parseToJsonElement(XrayVlessConfig.build(profile, socks)).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject
        val runtimePrimary = pair.primary.copy(host = ManagedEntryDns.FALLBACK_PRIMARY_IP)
        val config = Json.parseToJsonElement(XrayEntryFailoverConfig.buildVless(profile, socks, runtimePrimary, pair.backup,
            socks.copy(port = 14081), socks.copy(port = 14082), "/private/test/api.sock")).jsonObject
        val entries = config.getValue("outbounds").jsonArray.map { it.jsonObject }
            .filter { it["tag"]?.jsonPrimitive?.content in setOf("entry-primary", "entry-backup") }
        assertEquals(listOf("178.208.70.181", "48.103.218.29"), entries.map {
            it.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject.getValue("address").jsonPrimitive.content
        })
        for (entry in entries) assertEquals(original["streamSettings"], entry["streamSettings"])
        assertEquals(source, profile.uri)
        assertEquals("ru-kvn.meesharr.space", profile.entryFailover!!.primary.host)
    }

    @Test fun awgKnownMetadataPinsDistinctEntriesAndRetainsTheOriginalKeysAndObfuscation() {
        val raw = "[Interface]\nPrivateKey = private-placeholder\nAddress = 10.0.0.2/32\nJc = 5\n[Peer]\nPublicKey = public-placeholder\nEndpoint = awg-legacy-primary.meesharr.space:18443\nAllowedIPs = 0.0.0.0/0\n"
        val profile = VpnProfileConfig(type = "awg", rawConfig = raw, entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("awg-legacy-primary.meesharr.space", 18443), VpnEntryEndpoint("jh-kvn.meesharr.space", 18443)))
        val pair = assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        assertEquals(ManagedEntryDns.PRIMARY_HOST, pair.primary.host)
        assertEquals("48.103.218.29", pair.backup.host)
        assertEquals(raw.replace("awg-legacy-primary.meesharr.space:18443", "178.208.70.181:18443"),
            VpnEntryFailoverPlanner.forEndpoint(profile, pair.primary.copy(host = ManagedEntryDns.FALLBACK_PRIMARY_IP)).rawConfig)
        assertEquals(raw, profile.rawConfig)
    }

    @Test fun aCustomSuppliedBackupIsNeverReplacedByTheManagedPair() {
        val profile = VpnProfileConfig(type = "vless", uri = uri("ru-kvn.meesharr.space"), entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("ru-kvn.meesharr.space", 443), VpnEntryEndpoint("custom.example", 9443)))
        assertEquals(profile.entryFailover, VpnEntryFailoverPlanner.plan(profile))
    }

    @Test fun knownHostnameMetadataAcceptsTheProviderIpChangeWithoutChangingSavedProfiles() {
        for (primary in listOf("185.11.247.237", "178.208.70.181", ManagedEntryDns.PRIMARY_HOST)) {
            val source = uri("ru-kvn.meesharr.space")
            val profile = VpnProfileConfig(type = "vless", uri = source, entryFailover = VpnEntryFailoverConfig(
                VpnEntryEndpoint(primary, 443), VpnEntryEndpoint("jh-kvn.meesharr.space", 443)))
            val pair = assertNotNull(VpnEntryFailoverPlanner.plan(profile))
            assertEquals(ManagedEntryDns.PRIMARY_HOST, pair.primary.host)
            assertEquals("48.103.218.29", pair.backup.host)
            assertEquals(source, profile.uri)
            assertEquals(primary, profile.entryFailover!!.primary.host)
        }
    }

    @Test fun theProviderIpChangeCannotReplaceLiteralManualRoutesOrUnrelatedMetadata() {
        for (address in listOf("185.11.247.237", "178.208.70.181", "48.103.218.29")) {
            val manual = VpnProfileConfig(type = "vless", uri = uri(address).replace("%2Fvpn", "%2Fkvn-manual%2Feu1"))
            assertNull(VpnEntryFailoverPlanner.plan(manual))
        }
        val thirdParty = VpnProfileConfig(type = "vless", uri = uri("third-party.example"), entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("185.11.247.237", 443), VpnEntryEndpoint("jh-kvn.meesharr.space", 443)))
        assertNull(VpnEntryFailoverPlanner.plan(thirdParty))
        val wrongPrimary = thirdParty.copy(uri = uri("ru-kvn.meesharr.space"),
            entryFailover = thirdParty.entryFailover!!.copy(primary = VpnEntryEndpoint("203.0.113.9", 443)))
        assertNull(VpnEntryFailoverPlanner.plan(wrongPrimary))
    }

    @Test fun awgHostnameMetadataMigratesTheOldIpWithoutChangingItsIdentityOrRawConfiguration() {
        val raw = "[Interface]\nPrivateKey = private-placeholder\nAddress = 10.0.0.2/32\nJc = 5\n[Peer]\nPublicKey = public-placeholder\nPresharedKey = psk-placeholder\nEndpoint = awg-legacy-primary.meesharr.space:18443\nAllowedIPs = 0.0.0.0/0\n"
        val profile = VpnProfileConfig(type = "awg", rawConfig = raw, entryFailover = VpnEntryFailoverConfig(
            VpnEntryEndpoint("185.11.247.237", 18443), VpnEntryEndpoint("48.103.218.29", 18443)))
        val pair = assertNotNull(VpnEntryFailoverPlanner.plan(profile))
        assertEquals(ManagedEntryDns.PRIMARY_HOST, pair.primary.host)
        assertEquals(raw.replace("awg-legacy-primary.meesharr.space:18443", "178.208.70.181:18443"),
            VpnEntryFailoverPlanner.forEndpoint(profile, pair.primary.copy(host = ManagedEntryDns.FALLBACK_PRIMARY_IP)).rawConfig)
        assertEquals(raw, profile.rawConfig)
    }

    private fun uri(host: String) = "vless://00000000-0000-4000-8000-000000000000@$host:443?type=ws&security=tls&sni=ru-kvn.meesharr.space&host=ru-kvn.meesharr.space&path=%2Fvpn#RuWeb"
}
