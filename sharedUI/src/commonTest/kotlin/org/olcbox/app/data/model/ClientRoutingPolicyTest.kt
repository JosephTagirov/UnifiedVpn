package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.*

class ClientRoutingPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun normalizesAndRoundTripsPolicy() {
        val policy = assertNotNull(ClientRoutingPolicy(revision = "sha256:abc-123", directDomains = listOf(
            "domain:Example.TEST", "full:api.example.test",
            "regexp:^videostun[a-z0-9-]*\\.okcdn\\.ru$")).normalizedOrNull())
        assertEquals("domain:example.test", policy.directDomains.first())
        val profile = VpnProfileConfig(type = "vless", uri = "vless://test@example.test:443", routingPolicy = policy)
        assertEquals(policy, json.decodeFromString(VpnProfileConfig.serializer(),
            json.encodeToString(VpnProfileConfig.serializer(), profile)).normalized().routingPolicy)
    }

    @Test fun rejectsUnsafeUnsupportedAndDuplicateRules() {
        for (rule in listOf("example.test", "domain:*.example.test", "domain:ru", "domain:127.0.0.1",
            "full:https://example.test", "domain:a..test", "domain:-bad.test", "domain:example.test:443",
            "domain:example.test\n", "regexp:.*", "regexp:^(a+)+$", "geosite:ru", "domain:пример.рф")) {
            assertNull(ClientRoutingPolicy(revision = "r1", directDomains = listOf(rule)).normalizedOrNull(), rule)
        }
        assertNull(ClientRoutingPolicy(revision = "r1",
            directDomains = listOf("domain:Example.test", "domain:example.test")).normalizedOrNull())
    }

    @Test fun enforcesVersionRevisionAndRuleBounds() {
        val valid = ClientRoutingPolicy(revision = "r1", directDomains = listOf("domain:example.test"))
        assertNull(valid.copy(version = 2).normalizedOrNull())
        for (revision in listOf("", "x".repeat(129), "r 1")) assertNull(valid.copy(revision = revision).normalizedOrNull())
        assertNull(valid.copy(directDomains = emptyList()).normalizedOrNull())
        assertNull(valid.copy(directDomains = List(ClientRoutingPolicy.MAX_DOMAIN_RULES + 1) { "domain:d$it.example.test" }).normalizedOrNull())
        assertNotNull(valid.copy(directDomains = List(ClientRoutingPolicy.MAX_DOMAIN_RULES) { "domain:d$it.example.test" }).normalizedOrNull())
        assertNull(valid.copy(directDomains = listOf("domain:" + "a".repeat(513))).normalizedOrNull())
    }

    @Test fun malformedOptionalStoredPolicyKeepsProfileReadableWithoutPolicy() {
        for (policy in listOf("""{"version":1,"revision":"r1","direct_domains":[7]}""",
            """{"version":1,"revision":"r1","direct_domains":"domain:example.test"}""",
            """{"version":8,"revision":"future","direct_domains":["domain:example.test"]}""")) {
            val profile = json.decodeFromString(VpnProfileConfig.serializer(),
                """{"type":"vless","uri":"vless://test@example.test:443","routing_policy":$policy}""").normalized()
            assertEquals("vless://test@example.test:443", profile.uri)
            assertNull(profile.routingPolicy)
        }
    }

    @Test fun rtcPolicySurvivesNormalizationAndBackup() {
        val policy = ClientRoutingPolicy(revision = "r1", directDomains = listOf("domain:example.test"))
        val rtc = LocationEntry.from("rtc", LocationConfig("RTC", "room", "a".repeat(64)))
        val entry = rtc.copy(vpnProfile = rtc.profile.copy(routingPolicy = policy)).normalized()
        val bundle = LocationBundleV4(locations = listOf(entry)).normalized()
        val restored = json.decodeFromString(LocationBundleV4.serializer(),
            json.encodeToString(LocationBundleV4.serializer(), bundle)).normalized()
        assertEquals(policy, restored.locations.single().profile.routingPolicy)
        assertEquals("room", restored.locations.single().location.id)
    }
}

