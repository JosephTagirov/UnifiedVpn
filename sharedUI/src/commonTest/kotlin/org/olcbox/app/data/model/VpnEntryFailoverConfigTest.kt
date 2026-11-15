package org.olcbox.app.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VpnEntryFailoverConfigTest {
    private val codec = Json { ignoreUnknownKeys = true }

    @Test
    fun existingProfileWithoutEntryMetadataRemainsCompatible() {
        val profile = codec.decodeFromString<VpnProfileConfig>(
            """{"type":"vless","name":"Phone","uri":"vless://synthetic"}"""
        )
        assertNull(profile.entryFailover)
        assertEquals("vless://synthetic", profile.normalized().uri)
    }

    @Test
    fun orderedPairRoundTripsWithoutChangingCredentialsOrProfileName() {
        val raw = "synthetic private configuration"
        val profile = VpnProfileConfig(
            type = "amnezia_wg", name = "Phone", rawConfig = raw,
            entryFailover = VpnEntryFailoverConfig(
                VpnEntryEndpoint("ru-kvn.meesharr.space", 18443, "RuWeb"),
                VpnEntryEndpoint("jh-kvn.meesharr.space", 18443, "Justhost")
            )
        )
        val encoded = codec.encodeToString(profile)
        val decoded = codec.decodeFromString<VpnProfileConfig>(encoded).normalized()
        assertEquals(profile, decoded)
        assertEquals(raw, decoded.rawConfig)
        assertEquals("Phone", decoded.name)
        assertEquals("RuWeb", decoded.entryFailover?.primary?.name)
    }

    @Test
    fun equivalentDialTargetsDoNotPretendToProvideRedundancy() {
        val pair = VpnEntryFailoverConfig(
            VpnEntryEndpoint("RU-KVN.MEESHARR.SPACE.", 443),
            VpnEntryEndpoint("ru-kvn.meesharr.space", 443)
        )
        assertNull(pair.normalizedOrNull())
    }

    @Test
    fun endpointIsAnAddressNotAUrlOrCredentials() {
        for (host in listOf("https://example.com", "user@example.com", "example.com/path", "example.com:443", "example.com\n", "bad..host")) {
            assertNull(VpnEntryEndpoint(host, 443).normalizedOrNull(), host)
        }
        assertNull(VpnEntryEndpoint("example.com", 0).normalizedOrNull())
        assertNull(VpnEntryEndpoint("example.com", 65536).normalizedOrNull())
    }

    @Test
    fun ipv6AddressIsKeptSeparateFromItsPort() {
        val address = VpnEntryEndpoint("[2001:db8::1]", 443).normalizedOrNull()
        assertNotNull(address)
        assertEquals("2001:db8::1", address.host)
        assertEquals(443, address.port)
    }
}
