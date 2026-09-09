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
    @SerialName("awg_config")
    val awgConfig: String
) {
    companion object {
        const val FORMAT = "unified-vpn-friend-access"
        const val CURRENT_VERSION = 2
    }
}

object FriendAccessPackageCodec {
    internal const val MAX_PACKAGE_JSON_BYTES = 256 * 1024
    internal const val MAX_VLESS_URI_CHARS = 16 * 1024
    internal const val MAX_AWG_CONFIG_BYTES = 64 * 1024
    internal const val MAX_OLCRTC_LOCATIONS = 128

    private const val MAX_PACKAGE_JSON_CHARS = MAX_PACKAGE_JSON_BYTES
    private const val MAX_AWG_LINE_CHARS = 2 * 1024
    private val base64KeyPattern = Regex("^[A-Za-z0-9+/]{43}=$")
    private val awgKeyPattern = Regex("^[A-Za-z][A-Za-z0-9]*$")
    private val interfaceKeyOrder = listOf(
        "Address",
        "DNS",
        "MTU",
        "PrivateKey",
        "Jc",
        "Jmin",
        "Jmax",
        "S1",
        "S2",
        "H1",
        "H2",
        "H3",
        "H4"
    )
    private val peerKeyOrder = listOf(
        "PublicKey",
        "PresharedKey",
        "AllowedIPs",
        "Endpoint",
        "PersistentKeepalive"
    )
    private val requiredInterfaceKeys = setOf(
        "address",
        "privatekey",
        "jc",
        "jmin",
        "jmax",
        "s1",
        "s2",
        "h1",
        "h2",
        "h3",
        "h4"
    )
    private val requiredPeerKeys = setOf(
        "publickey",
        "presharedkey",
        "allowedips",
        "endpoint"
    )
    private val allowedInterfaceKeys = interfaceKeyOrder.mapTo(mutableSetOf()) { it.lowercase() }
    private val allowedPeerKeys = peerKeyOrder.mapTo(mutableSetOf()) { it.lowercase() }

    private val json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun create(
        source: LocationBundleV4,
        vlessUri: String,
        awgConfig: String
    ): FriendAccessPackage {
        val normalizedVless = validateVlessUri(vlessUri)
        val normalizedAwg = validateAndNormalizeAwgConfig(awgConfig)
        val olcRtcLocations = source.normalized().locations
            .filter { it.profile.isOlcRtc() && it.isComplete() }
            .map { entry ->
                entry.copy(
                    subscriptionUrl = null,
                    metadata = null,
                    legacySubscriptionUrl = null
                ).normalized()
            }
        require(olcRtcLocations.isNotEmpty()) {
            "Add an olcRTC profile before creating a friend package"
        }
        require(olcRtcLocations.size <= MAX_OLCRTC_LOCATIONS) {
            "Friend package contains too many olcRTC profiles"
        }

        return FriendAccessPackage(
            olcRtcLocations = olcRtcLocations,
            vlessUri = normalizedVless,
            awgConfig = normalizedAwg
        ).validated()
    }

    fun encode(value: FriendAccessPackage): String {
        val encoded = json.encodeToString(value.validated())
        require(encoded.encodeToByteArray().size <= MAX_PACKAGE_JSON_BYTES) {
            "Friend package is too large"
        }
        return encoded
    }

    fun decodeOrNull(text: String): FriendAccessPackage? {
        if (text.length > MAX_PACKAGE_JSON_CHARS) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith('{') || !trimmed.contains(FriendAccessPackage.FORMAT)) return null
        if (trimmed.encodeToByteArray().size > MAX_PACKAGE_JSON_BYTES) return null
        return runCatching {
            json.decodeFromString<FriendAccessPackage>(trimmed).validated()
        }.getOrNull()
    }

    fun profilesImportText(value: FriendAccessPackage): String {
        val packageValue = value.validated()
        val usedIds = packageValue.olcRtcLocations.mapTo(mutableSetOf()) { it.storageId }

        fun nextStorageId(base: String): String {
            if (usedIds.add(base)) return base
            var suffix = 2
            while (!usedIds.add("${base}_${suffix}")) suffix++
            return "${base}_${suffix}"
        }

        val vlessEntry = LocationEntry.fromProfile(
            storageId = nextStorageId("friend_vless"),
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_VLESS,
                name = "VLESS",
                uri = packageValue.vlessUri,
                rawConfig = packageValue.vlessUri
            )
        )
        val awgEntry = LocationEntry.fromProfile(
            storageId = nextStorageId("friend_amnezia_wg"),
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                name = "AmneziaWG",
                rawConfig = packageValue.awgConfig
            )
        )
        return json.encodeToString(
            LocationBundleV4(
                activeLocationId = packageValue.olcRtcLocations.first().storageId,
                locations = packageValue.olcRtcLocations + vlessEntry + awgEntry
            )
        )
    }

    private fun FriendAccessPackage.validated(): FriendAccessPackage {
        require(format == FriendAccessPackage.FORMAT) { "Unsupported friend package" }
        require(version == FriendAccessPackage.CURRENT_VERSION) {
            "Unsupported friend package version"
        }
        require(olcRtcLocations.isNotEmpty()) { "Friend package has no valid olcRTC profile" }
        require(olcRtcLocations.size <= MAX_OLCRTC_LOCATIONS) {
            "Friend package contains too many olcRTC profiles"
        }

        val normalizedLocations = LocationBundleV4(locations = olcRtcLocations)
            .normalized()
            .locations
        require(normalizedLocations.size == olcRtcLocations.size) {
            "Friend package contains invalid olcRTC profiles"
        }
        require(normalizedLocations.all { it.profile.isOlcRtc() && it.isComplete() }) {
            "Friend package contains invalid olcRTC profiles"
        }
        val safeLocations = normalizedLocations.map { entry ->
            entry.copy(
                subscriptionUrl = null,
                metadata = null,
                legacySubscriptionUrl = null
            ).normalized()
        }

        return copy(
            olcRtcLocations = safeLocations,
            vlessUri = validateVlessUri(vlessUri),
            awgConfig = validateAndNormalizeAwgConfig(awgConfig)
        )
    }

    internal fun validateVlessUri(value: String): String {
        val normalized = value.trim()
        require(normalized.length in 1..MAX_VLESS_URI_CHARS) { "VLESS link is too large" }
        require(normalized.startsWith("vless://", ignoreCase = true)) {
            "Enter a valid VLESS link"
        }
        require(normalized.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter a valid VLESS link"
        }

        val authority = normalized
            .substring("vless://".length)
            .substringBefore('#')
            .substringBefore('?')
        val at = authority.lastIndexOf('@')
        require(at in 1 until authority.lastIndex) { "Enter a valid VLESS link" }
        val endpoint = authority.substring(at + 1)
        val host: String
        val portText: String
        if (endpoint.startsWith('[')) {
            val closing = endpoint.indexOf(']')
            require(closing > 1 && closing + 2 < endpoint.length && endpoint[closing + 1] == ':') {
                "Enter a valid VLESS link"
            }
            host = endpoint.substring(1, closing)
            portText = endpoint.substring(closing + 2)
        } else {
            val separator = endpoint.lastIndexOf(':')
            require(separator in 1 until endpoint.lastIndex) { "Enter a valid VLESS link" }
            host = endpoint.substring(0, separator)
            portText = endpoint.substring(separator + 1)
        }
        require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter a valid VLESS link"
        }
        require(portText.toIntOrNull()?.let { it in 1..65535 } == true) {
            "Enter a valid VLESS link"
        }
        return normalized
    }

    private fun validateAndNormalizeAwgConfig(value: String): String {
        require(value.encodeToByteArray().size in 1..MAX_AWG_CONFIG_BYTES) {
            "AmneziaWG configuration is empty or too large"
        }
        val normalizedLines = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith(';') }
            .toList()
        require(normalizedLines.isNotEmpty()) { "AmneziaWG configuration is empty" }
        require(normalizedLines.all { line ->
            line.length <= MAX_AWG_LINE_CHARS && line.none { it.isISOControl() }
        }) { "AmneziaWG configuration contains an invalid line" }

        val interfaceValues = linkedMapOf<String, String>()
        val peerValues = linkedMapOf<String, String>()
        var currentSection: String? = null
        var interfaceSections = 0
        var peerSections = 0

        normalizedLines.forEach { line ->
            if (line.startsWith('[') && line.endsWith(']')) {
                currentSection = line.substring(1, line.lastIndex).trim().lowercase()
                require(currentSection == "interface" || currentSection == "peer") {
                    "AmneziaWG configuration contains an unsupported section"
                }
                if (currentSection == "interface") interfaceSections++ else peerSections++
                return@forEach
            }

            val section = currentSection
                ?: error("AmneziaWG configuration value appears before a section")
            val separator = line.indexOf('=')
            require(separator in 1 until line.lastIndex) {
                "AmneziaWG configuration contains an invalid value"
            }
            val key = line.substring(0, separator).trim()
            val keyLower = key.lowercase()
            val fieldValue = line.substring(separator + 1).trim()
            require(awgKeyPattern.matches(key) && fieldValue.isNotEmpty()) {
                "AmneziaWG configuration contains an invalid value"
            }
            val target = if (section == "interface") interfaceValues else peerValues
            val allowed = if (section == "interface") allowedInterfaceKeys else allowedPeerKeys
            require(keyLower in allowed) { "AmneziaWG configuration contains an unsupported field" }
            require(target.put(keyLower, fieldValue) == null) {
                "AmneziaWG configuration contains a duplicate field"
            }
        }

        require(interfaceSections == 1 && peerSections == 1) {
            "AmneziaWG configuration must contain one Interface and one Peer section"
        }
        require(interfaceValues.keys.containsAll(requiredInterfaceKeys)) {
            "AmneziaWG Interface section is incomplete"
        }
        require(peerValues.keys.containsAll(requiredPeerKeys)) {
            "AmneziaWG Peer section is incomplete"
        }
        require(interfaceValues.getValue("address").contains('/')) {
            "AmneziaWG interface address is invalid"
        }
        require(base64KeyPattern.matches(interfaceValues.getValue("privatekey"))) {
            "AmneziaWG private key is invalid"
        }
        require(base64KeyPattern.matches(peerValues.getValue("publickey"))) {
            "AmneziaWG peer public key is invalid"
        }
        require(base64KeyPattern.matches(peerValues.getValue("presharedkey"))) {
            "AmneziaWG preshared key is invalid"
        }
        require(peerValues.getValue("allowedips").isNotBlank()) {
            "AmneziaWG AllowedIPs value is invalid"
        }
        validateEndpoint(peerValues.getValue("endpoint"))
        listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4").forEach { key ->
            require(interfaceValues.getValue(key).toIntOrNull() != null) {
                "AmneziaWG obfuscation parameters are invalid"
            }
        }
        interfaceValues["mtu"]?.let {
            require(it.toIntOrNull()?.let { value -> value in 576..65_535 } == true) {
                "AmneziaWG MTU is invalid"
            }
        }
        peerValues["persistentkeepalive"]?.let {
            require(it.toIntOrNull()?.let { value -> value in 0..65_535 } == true) {
                "AmneziaWG keepalive is invalid"
            }
        }

        fun canonicalSection(
            name: String,
            order: List<String>,
            values: Map<String, String>
        ): String = buildString {
            append('[').append(name).appendLine(']')
            order.forEach { key ->
                values[key.lowercase()]?.let { fieldValue ->
                    append(key).append(" = ").appendLine(fieldValue)
                }
            }
        }.trimEnd()

        val canonical = canonicalSection("Interface", interfaceKeyOrder, interfaceValues) +
            "\n\n" + canonicalSection("Peer", peerKeyOrder, peerValues)
        require(canonical.encodeToByteArray().size <= MAX_AWG_CONFIG_BYTES) {
            "AmneziaWG configuration is too large"
        }
        return canonical
    }

    private fun validateEndpoint(value: String) {
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            require(closing > 1 && closing + 2 < value.length && value[closing + 1] == ':') {
                "AmneziaWG endpoint is invalid"
            }
            host = value.substring(1, closing)
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            require(separator in 1 until value.lastIndex) { "AmneziaWG endpoint is invalid" }
            host = value.substring(0, separator)
            portText = value.substring(separator + 1)
        }
        require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() }) {
            "AmneziaWG endpoint is invalid"
        }
        require(portText.toIntOrNull()?.let { it in 1..65535 } == true) {
            "AmneziaWG endpoint is invalid"
        }
    }
}
