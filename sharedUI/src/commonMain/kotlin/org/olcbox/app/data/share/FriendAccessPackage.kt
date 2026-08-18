package org.olcbox.app.data.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig

@Serializable
data class FriendAccessPackage(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    @SerialName("olcrtc_locations")
    val olcRtcLocations: List<LocationEntry>,
    @SerialName("vless_uri")
    val vlessUri: String,
    val amnezia: FriendAmneziaServer
) {
    companion object {
        const val FORMAT = "unified-vpn-friend-access"
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class FriendAmneziaServer(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    @SerialName("host_key_algorithm")
    val hostKeyAlgorithm: String,
    @SerialName("host_public_key")
    val hostPublicKey: String,
    @SerialName("host_fingerprint")
    val hostFingerprint: String
) {
    fun normalized(): FriendAmneziaServer = copy(
        host = host.trim().removePrefix("[").removeSuffix("]"),
        username = username.trim(),
        hostKeyAlgorithm = hostKeyAlgorithm.trim(),
        hostPublicKey = hostPublicKey.trim(),
        hostFingerprint = hostFingerprint.trim()
    )

    fun validate() {
        val value = normalized()
        require(value.host.isNotBlank()) { "Server address is required" }
        require(value.host.none { it.isWhitespace() || it.isISOControl() }) {
            "Server address must not contain spaces or control characters"
        }
        require(value.port in 1..65535) { "SSH port must be between 1 and 65535" }
        require(value.username.isNotBlank()) { "SSH login is required" }
        require(value.password.isNotEmpty()) { "SSH password is required" }
        require(value.hostKeyAlgorithm.isNotBlank()) { "SSH host-key algorithm is missing" }
        require(value.hostPublicKey.isNotBlank()) { "SSH host public key is missing" }
        require(value.hostFingerprint.startsWith("SHA256:")) { "SSH host fingerprint is invalid" }
    }
}

object FriendAccessPackageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun create(
        source: LocationBundleV4,
        vlessUri: String,
        amnezia: FriendAmneziaServer
    ): FriendAccessPackage {
        val normalizedVless = vlessUri.trim()
        require(normalizedVless.startsWith("vless://", ignoreCase = true)) {
            "Enter a valid VLESS link"
        }
        amnezia.validate()

        val olcRtcLocations = source.normalized().locations
            .filter { it.profile.isOlcRtc() }
            .map { entry ->
                entry.copy(
                    subscriptionUrl = null,
                    metadata = null,
                    legacySubscriptionUrl = null
                ).normalized()
            }
        require(olcRtcLocations.isNotEmpty()) { "Add an olcRTC profile before creating a friend package" }

        return FriendAccessPackage(
            olcRtcLocations = olcRtcLocations,
            vlessUri = normalizedVless,
            amnezia = amnezia.normalized()
        )
    }

    fun encode(value: FriendAccessPackage): String = json.encodeToString(value)

    fun decodeOrNull(text: String): FriendAccessPackage? {
        val trimmed = text.trim()
        if (!trimmed.startsWith('{') || !trimmed.contains(FriendAccessPackage.FORMAT)) return null
        return runCatching {
            json.decodeFromString<FriendAccessPackage>(trimmed).validated()
        }.getOrNull()
    }

    fun profilesImportText(value: FriendAccessPackage): String {
        val packageValue = value.validated()
        val usedIds = packageValue.olcRtcLocations.mapTo(mutableSetOf()) { it.storageId }
        var vlessId = "friend_vless"
        var suffix = 2
        while (vlessId in usedIds) {
            vlessId = "friend_vless_${suffix++}"
        }
        val vlessEntry = LocationEntry.fromProfile(
            storageId = vlessId,
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_VLESS,
                name = "VLESS",
                uri = packageValue.vlessUri,
                rawConfig = packageValue.vlessUri
            )
        )
        return json.encodeToString(
            LocationBundleV4(
                activeLocationId = packageValue.olcRtcLocations.first().storageId,
                locations = packageValue.olcRtcLocations + vlessEntry
            )
        )
    }

    private fun FriendAccessPackage.validated(): FriendAccessPackage {
        require(format == FriendAccessPackage.FORMAT) { "Unsupported friend package" }
        require(version == FriendAccessPackage.CURRENT_VERSION) { "Unsupported friend package version" }
        require(vlessUri.trim().startsWith("vless://", ignoreCase = true)) { "Friend package has no valid VLESS link" }
        amnezia.validate()
        val normalizedLocations = LocationBundleV4(locations = olcRtcLocations)
            .normalized()
            .locations
            .filter { it.profile.isOlcRtc() }
        require(normalizedLocations.isNotEmpty()) { "Friend package has no valid olcRTC profile" }
        return copy(
            olcRtcLocations = normalizedLocations,
            vlessUri = vlessUri.trim(),
            amnezia = amnezia.normalized()
        )
    }
}
