package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

class RoutingPolicySubscriptionTest {
    private val identity = object : DeviceIdentityProvider { override suspend fun hwid() = "test-device" }
    private val uri = "vless://test@example.test:443#Test"
    private val url = "https://subscription.example.test/sub"

    private fun envelope(payload: String = uri, revision: String = "r1", rule: String = "domain:example.test"): String =
        buildJsonObject {
            put("format", "unifiedvpn-subscription-v1")
            put("profiles", payload)
            put("routing_policy", buildJsonObject {
                put("version", 1); put("revision", revision)
                put("direct_domains", JsonArray(listOf(JsonPrimitive(rule))))
            })
        }.toString()

    @Test fun refreshUpdatesPolicyPreservingIdentityUrlAndBackup() = runTest {
        var body = envelope()
        val source = Source()
        val repository = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }),
            deviceIdentityProvider = identity)
        assertTrue(repository.importText(url))
        val first = assertNotNull(source.stored)
        assertEquals("r1", first.locations.single().profile.routingPolicy?.revision)
        body = envelope(revision = "r2", rule = "full:api.example.test")
        assertEquals(1, repository.refreshSubscription(url))
        val updated = assertNotNull(source.stored)
        assertEquals(first.activeLocationId, updated.activeLocationId)
        assertEquals(first.locations.single().storageId, updated.locations.single().storageId)
        assertEquals(url, updated.locations.single().subscriptionUrl)
        assertEquals("r2", updated.locations.single().profile.routingPolicy?.revision)
        assertEquals(listOf("full:api.example.test"), updated.locations.single().profile.routingPolicy?.directDomains)
        val restored = Source()
        assertTrue(LocationsRepositoryImpl(restored).importText(repository.exportBundle()))
        assertEquals("r2", restored.stored?.locations?.single()?.profile?.routingPolicy?.revision)
    }

    @Test fun invalidRefreshPreservesWorkingProfilesAndPolicy() = runTest {
        var body = envelope()
        val source = Source()
        val repository = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }),
            deviceIdentityProvider = identity)
        assertTrue(repository.importText(url))
        val before = assertNotNull(source.stored)
        body = envelope(revision = "bad", rule = "regexp:.*")
        assertEquals(0, repository.refreshSubscription(url))
        val after = assertNotNull(source.stored)
        assertEquals(before.activeLocationId, after.activeLocationId)
        assertEquals(before.locations.single().storageId, after.locations.single().storageId)
        assertEquals(before.locations.single().profile, after.locations.single().profile)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun acceptsEncodedPayloadAndEnvelopeAndKeepsOldSubscriptionsCompatible() = runTest {
        val encodedPayload = Base64.encode(uri.encodeToByteArray())
        val encodedEnvelope = Base64.encode(envelope(encodedPayload).encodeToByteArray())
        val source = Source()
        assertTrue(LocationsRepositoryImpl(source).importText(encodedEnvelope))
        assertEquals("r1", source.stored?.locations?.single()?.profile?.routingPolicy?.revision)
        for (body in listOf(uri, encodedPayload)) {
            val old = Source()
            assertTrue(LocationsRepositoryImpl(old).importText(body))
            assertNull(old.stored?.locations?.single()?.profile?.routingPolicy)
        }
    }

    @Test fun existingLocationEnvelopeAppliesPolicyAndInvalidRootCannotUseCompatibilityFallback() = runTest {
        val entry = LocationEntry.fromProfile("test", VpnProfileConfig(type = "vless", uri = uri))
        val entryJson = Json.encodeToString(LocationEntry.serializer(), entry)
        fun bundle(policy: String) = """{"version":5,"locations":[$entryJson],"routing_policy":$policy}"""
        val valid = """{"version":1,"revision":"root-r1","direct_domains":["domain:example.test"]}"""
        val source = Source()
        val repository = LocationsRepositoryImpl(source)
        assertTrue(repository.importText(bundle(valid)))
        assertEquals("root-r1", source.stored?.locations?.single()?.profile?.routingPolicy?.revision)
        val before = source.stored
        assertFalse(repository.importText(bundle("""{"version":1,"revision":"bad","direct_domains":["regexp:.*"]}""")))
        assertEquals(before, source.stored)
    }

    @Test fun rejectsNestedOrUnknownPolicyEnvelopesWithoutMutation() = runTest {
        val source = Source()
        val repository = LocationsRepositoryImpl(source)
        assertTrue(repository.importText(uri))
        val before = source.stored
        assertFalse(repository.importText(envelope(envelope())))
        assertFalse(repository.importText(envelope().replace("unifiedvpn-subscription-v1", "unifiedvpn-subscription-v2")))
        assertEquals(before, source.stored)
    }

    @Test fun editorSavesPreservePolicyWhileRefreshWithoutPolicyReturnsToFallback() = runTest {
        var body = envelope()
        val source = Source()
        val repository = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }),
            deviceIdentityProvider = identity)
        assertTrue(repository.importText(url))
        val entry = assertNotNull(source.stored).locations.single()
        repository.saveProfile(entry.storageId, VpnProfileConfig(type = "vless", name = "Renamed", uri = uri))
        assertEquals("r1", source.stored?.locations?.single()?.profile?.routingPolicy?.revision)
        repository.saveLocation(entry.storageId, LocationConfig("RTC", "room", "a".repeat(64)))
        assertEquals("r1", source.stored?.locations?.single()?.profile?.routingPolicy?.revision)
        body = uri
        assertEquals(1, repository.refreshSubscription(url))
        assertNull(source.stored?.locations?.single()?.profile?.routingPolicy)
    }

    @Test fun scheduledRefreshReplacesPolicyWhenSubscriptionBecomesDue() = runTest {
        var now = 1_000L
        var body = envelope()
        val source = Source()
        val repository = LocationsRepositoryImpl(source, HttpClient(MockEngine { respond(body) }),
            deviceIdentityProvider = identity, nowEpochMs = { now })
        assertTrue(repository.importText(url))
        body = envelope(revision = "automatic-r2")
        assertEquals(0, repository.refreshDueSubscriptions())
        now += SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_MS + 1
        assertEquals(1, repository.refreshDueSubscriptions())
        assertEquals("automatic-r2", source.stored?.locations?.single()?.profile?.routingPolicy?.revision)
    }

    @Test fun policiesRemainScopedToTheirOwnSubscriptions() = runTest {
        val secondUrl = "https://second-subscription.example.test/sub"
        val secondUri = "vless://other@second.example.test:443#Second"
        val source = Source()
        val client = HttpClient(MockEngine { request ->
            respond(if (request.url.host == "subscription.example.test") envelope()
                else envelope(secondUri, "second-r1", "full:second.example.test"))
        })
        val repository = LocationsRepositoryImpl(source, client, deviceIdentityProvider = identity)
        assertTrue(repository.importText(url))
        assertTrue(repository.importText(secondUrl))
        val entries = assertNotNull(source.stored).locations
        assertEquals("r1", entries.single { it.subscriptionUrl == url }.profile.routingPolicy?.revision)
        assertEquals("second-r1", entries.single { it.subscriptionUrl == secondUrl }.profile.routingPolicy?.revision)
    }

    private class Source(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle() = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations() = emptyList<Pair<String, String>>()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }
}

