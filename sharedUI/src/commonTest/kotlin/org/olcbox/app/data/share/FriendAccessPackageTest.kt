package org.olcbox.app.data.share

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendAccessPackageTest {
    @Test
    fun packageCopiesOlcRtcSelectedVlessAndReadyAwgWithoutSshAccess() {
        val source = LocationBundleV4(
            locations = listOf(
                LocationEntry.from(
                    storageId = "shared_olcrtc",
                    location = LocationConfig(
                        name = "Shared room",
                        id = "https://meet.example.invalid/friend-room",
                        key = "a".repeat(64),
                        bypassProvider = LocationConfig.PROVIDER_JITSI,
                        transport = LocationConfig.TRANSPORT_DATACHANNEL
                    ),
                    subscriptionUrl = "https://private.example.invalid/subscription"
                ),
                LocationEntry.from(
                    storageId = "incomplete_olcrtc",
                    location = LocationConfig(name = "Incomplete", id = "room", key = "")
                ),
                LocationEntry.fromProfile(
                    storageId = "private_vless",
                    profile = VpnProfileConfig(
                        type = VpnProfileConfig.TYPE_VLESS,
                        uri = "vless://old-private-profile@example.invalid:443",
                        rawConfig = "vless://old-private-profile@example.invalid:443"
                    )
                ),
                LocationEntry.fromProfile(
                    storageId = "private_awg",
                    profile = VpnProfileConfig(
                        type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                        rawConfig = "old-private-awg-config"
                    )
                )
            )
        )

        val packageValue = FriendAccessPackageCodec.create(
            source = source,
            vlessUri = VALID_VLESS,
            awgConfig = VALID_AWG
        )
        val encoded = FriendAccessPackageCodec.encode(packageValue)
        val decoded = FriendAccessPackageCodec.decodeOrNull(encoded)
        val imported = Json { ignoreUnknownKeys = true }
            .decodeFromString<LocationBundleV4>(
                FriendAccessPackageCodec.profilesImportText(packageValue)
            )
            .normalized()

        assertEquals(FriendAccessPackage.CURRENT_VERSION, decoded?.version)
        assertEquals(1, packageValue.olcRtcLocations.size)
        assertEquals(null, packageValue.olcRtcLocations.single().subscriptionUrl)
        assertEquals(3, imported.locations.size)
        assertEquals(
            setOf(
                VpnProfileConfig.TYPE_OLCRTC,
                VpnProfileConfig.TYPE_VLESS,
                VpnProfileConfig.TYPE_AMNEZIA_WG
            ),
            imported.locations.map { it.profile.type }.toSet()
        )
        assertTrue(encoded.contains(VALID_VLESS))
        assertTrue(imported.locations.any { it.profile.rawConfig.orEmpty().contains("[Interface]") })
        assertFalse(encoded.contains("old-private-profile"))
        assertFalse(encoded.contains("old-private-awg-config"))
        assertFalse(encoded.contains("private.example.invalid"))
        assertFalse(encoded.contains("ssh_host"))
        assertFalse(encoded.contains("ssh_port"))
        assertFalse(encoded.contains("username"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("host_key_algorithm"))
        assertFalse(encoded.contains("host_public_key"))
        assertFalse(encoded.contains("host_fingerprint"))
        assertFalse(packageValue.awgConfig.contains("Self-hosted"))
    }

    @Test
    fun legacyCredentialPackageIsRejectedFailClosed() {
        val legacy = """
            {
              "format": "unified-vpn-friend-access",
              "version": 1,
              "olcrtc_locations": [],
              "vless_uri": "$VALID_VLESS",
              "amnezia": {
                "host": "203.0.113.10",
                "port": 22,
                "username": "root",
                "password": "must-not-be-imported",
                "hostFingerprint": "SHA256:legacy"
              }
            }
        """.trimIndent()

        assertNull(FriendAccessPackageCodec.decodeOrNull(legacy))
    }

    @Test
    fun versionTwoPackageWithInjectedCredentialBlockIsRejected() {
        val valid = FriendAccessPackageCodec.encode(
            FriendAccessPackageCodec.create(completeOlcRtcSource(), VALID_VLESS, VALID_AWG)
        )
        val injected = valid.replaceFirst(
            "{",
            """{"amnezia":{"host":"203.0.113.10","port":22,"username":"root","password":"reject-me"},"""
        )

        assertNull(FriendAccessPackageCodec.decodeOrNull(injected))
    }

    @Test
    fun invalidOrOversizedAwgConfigurationIsRejected() {
        val source = completeOlcRtcSource()
        val unsupportedField = VALID_AWG.replace(
            "PrivateKey =",
            "SshPassword = should-never-be-packaged\nPrivateKey ="
        )

        assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageCodec.create(source, VALID_VLESS, unsupportedField)
        }
        assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageCodec.create(
                source,
                VALID_VLESS,
                VALID_AWG + "\n#" + "x".repeat(FriendAccessPackageCodec.MAX_AWG_CONFIG_BYTES)
            )
        }
    }

    @Test
    fun malformedVlessLinkIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            FriendAccessPackageCodec.create(
                completeOlcRtcSource(),
                "vless://missing-port@example.invalid",
                VALID_AWG
            )
        }
    }

    private fun completeOlcRtcSource(): LocationBundleV4 = LocationBundleV4(
        locations = listOf(
            LocationEntry.from(
                storageId = "shared_olcrtc",
                location = LocationConfig(id = "friend-room", key = "a".repeat(64))
            )
        )
    )

    private companion object {
        const val VALID_VLESS =
            "vless://00000000-0000-4000-8000-000000000000@example.invalid:443?security=tls&type=tcp#Friend"

        val VALID_AWG = """
            # This source comment must not be exported.
            [Interface]
            Address = 10.8.1.2/32
            DNS = 1.1.1.1
            MTU = 1280
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Jc = 4
            Jmin = 10
            Jmax = 50
            S1 = 12
            S2 = 13
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
            PresharedKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = vpn.example.invalid:55424
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
