package org.olcbox.app.data.share

import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendAccessPackageTest {
    @Test
    fun packageCopiesOnlyOlcRtcAndSelectedVless() {
        val source = LocationBundleV4(
            locations = listOf(
                LocationEntry.from(
                    storageId = "shared_olcrtc",
                    location = LocationConfig(name = "Shared room", id = "room", key = "room-key")
                ),
                LocationEntry.fromProfile(
                    storageId = "private_vless",
                    profile = VpnProfileConfig(
                        type = VpnProfileConfig.TYPE_VLESS,
                        uri = "vless://old-private-profile",
                        rawConfig = "vless://old-private-profile"
                    )
                ),
                LocationEntry.fromProfile(
                    storageId = "private_awg",
                    profile = VpnProfileConfig(
                        type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                        rawConfig = "[Interface]\nPrivateKey = existing-private-key\n[Peer]\nPublicKey = server"
                    )
                )
            )
        )
        val server = FriendAmneziaServer(
            host = "203.0.113.10",
            username = "root",
            password = "ssh-secret",
            hostKeyAlgorithm = "ssh-ed25519",
            hostPublicKey = "cHVibGljLWtleQ==",
            hostFingerprint = "SHA256:test-fingerprint"
        )

        val packageValue = FriendAccessPackageCodec.create(
            source = source,
            vlessUri = "vless://new-profile",
            amnezia = server
        )
        val encoded = FriendAccessPackageCodec.encode(packageValue)
        val profiles = FriendAccessPackageCodec.profilesImportText(packageValue)

        assertEquals(1, packageValue.olcRtcLocations.size)
        assertTrue(encoded.contains("vless://new-profile"))
        assertFalse(encoded.contains("old-private-profile"))
        assertFalse(encoded.contains("existing-private-key"))
        assertTrue(profiles.contains("vless://new-profile"))
        assertFalse(profiles.contains("ssh-secret"))
    }
}
