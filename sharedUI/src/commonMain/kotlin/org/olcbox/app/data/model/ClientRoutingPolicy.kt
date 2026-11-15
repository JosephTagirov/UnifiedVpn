package org.olcbox.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Subscription-supplied destination exceptions, never executable/core configuration. */
@Serializable(with = ClientRoutingPolicySerializer::class)
data class ClientRoutingPolicy(
    val version: Int = CURRENT_VERSION,
    val revision: String = "",
    val directDomains: List<String> = emptyList()
) {
    fun normalizedOrNull(): ClientRoutingPolicy? {
        val rev = revision.trim()
        if (version != CURRENT_VERSION || !REVISION.matches(rev) ||
            directDomains.isEmpty() || directDomains.size > MAX_DOMAIN_RULES) return null
        val rules = directDomains.map { normalizeRule(it) ?: return null }
        if (rules.distinct().size != rules.size) return null
        return copy(revision = rev, directDomains = rules)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_DOMAIN_RULES = 16_384
        const val MAX_RULE_LENGTH = 512
        const val MAX_ENCODED_LENGTH = 2 * 1024 * 1024
        private val REVISION = Regex("[A-Za-z0-9._:-]{1,128}")
        // v1 preserves the published allowlist's sole regex without admitting arbitrary patterns.
        private const val VIDEO_STUN_RULE = "regexp:^videostun[a-z0-9-]*\\.okcdn\\.ru$"

        fun fromJsonOrNull(element: JsonElement?): ClientRoutingPolicy? {
            val root = element as? JsonObject ?: return null
            val version = root["version"] as? JsonPrimitive ?: return null
            val revision = root["revision"] as? JsonPrimitive ?: return null
            val domains = root["direct_domains"] as? JsonArray ?: return null
            if (version.isString || !revision.isString || domains.size > MAX_DOMAIN_RULES) return null
            val rules = domains.map { value ->
                val item = value as? JsonPrimitive ?: return null
                if (!item.isString) return null
                item.contentOrNull ?: return null
            }
            return ClientRoutingPolicy(version.intOrNull ?: return null,
                revision.contentOrNull ?: return null, rules).normalizedOrNull()
        }

        private fun normalizeRule(value: String): String? {
            if (value.length > MAX_RULE_LENGTH || value.any { it.code <= 32 || it.code >= 127 }) return null
            if (value == VIDEO_STUN_RULE) return value
            val prefix = when {
                value.startsWith("domain:") -> "domain:"
                value.startsWith("full:") -> "full:"
                else -> return null
            }
            val host = value.removePrefix(prefix).lowercase()
            if (host.length !in 3..253 || '.' !in host || host.all { it.isDigit() || it == '.' }) return null
            if (!host.split('.').all { label ->
                label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }) return null
            return prefix + host
        }
    }
}

/** Invalid optional policy data must not make saved VPN profiles unreadable. */
object ClientRoutingPolicySerializer : KSerializer<ClientRoutingPolicy> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClientRoutingPolicy")
    override fun deserialize(decoder: Decoder): ClientRoutingPolicy {
        val source = decoder as? JsonDecoder ?: error("Routing policy requires JSON")
        return ClientRoutingPolicy.fromJsonOrNull(source.decodeJsonElement()) ?: ClientRoutingPolicy(version = 0)
    }
    override fun serialize(encoder: Encoder, value: ClientRoutingPolicy) {
        val target = encoder as? JsonEncoder ?: error("Routing policy requires JSON")
        target.encodeJsonElement(buildJsonObject {
            put("version", value.version)
            put("revision", value.revision)
            put("direct_domains", JsonArray(value.directDomains.map(::JsonPrimitive)))
        })
    }
}

