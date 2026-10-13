package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopOpenFluxBrowserBootstrapTest {
    private val id = "a".repeat(32)
    private val url = "https://docs.yandex.ru/docs/view?test=public-placeholder"

    @Test
    fun acceptsOnlyTheExactNativeRequestMarker() {
        assertEquals(id, OpenFluxBrowserProtocol.requestId("OPENFLUX_BROWSER_VERIFY $id"))
        listOf("OPENFLUX_BROWSER_VERIFY ${id.uppercase()}", "OPENFLUX_BROWSER_VERIFY $id\n",
            "log OPENFLUX_BROWSER_VERIFY $id", "OPENFLUX_BROWSER_VERIFY $id extra")
            .forEach { assertNull(OpenFluxBrowserProtocol.requestId(it)) }
    }

    @Test
    fun helperRequestContainsNoProfileOrEncryptionKey() {
        val request = Json.parseToJsonElement(OpenFluxBrowserProtocol.helperRequest(id, url, Path.of("owned"))).jsonObject
        assertEquals(setOf("id", "document_url", "user_data_dir"), request.keys)
        assertEquals(url, request.getValue("document_url").jsonPrimitive.content)
    }

    @Test
    fun acceptsAnAnonymousHttpsCookieAndSessionExpiry() {
        assertNotNull(checked(cookie()))
        assertNotNull(checked(cookie(domain = "docs.yandex.ru", expires = 200)))
    }

    @Test
    fun preservesOnlyBoundedBrowserIdentityOnSuccessfulReply() {
        val base = Json.parseToJsonElement(response(listOf(cookie()))).jsonObject
        val matched = JsonObject(base + ("user_agent" to JsonPrimitive("SyntheticBrowser/1.0"))).toString()
        assertEquals(matched, OpenFluxBrowserProtocol.checkedResponse(matched, id, url, 100))
        listOf("", " leading", "trailing ", "a\r\nb", "a\tb", "a\u0000b", "a\u007fb", "a\u0080b", "a".repeat(1025)).forEach { agent ->
            val raw = JsonObject(base + ("user_agent" to JsonPrimitive(agent))).toString()
            assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, url, 100))
        }
        listOf("null", "true", "123", "[]", "{}").forEach { value ->
            val raw = JsonObject(base + ("user_agent" to Json.parseToJsonElement(value))).toString()
            assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, url, 100))
        }
        val failure = Json.parseToJsonElement(OpenFluxBrowserProtocol.failure(id)).jsonObject
        assertNull(OpenFluxBrowserProtocol.checkedResponse(JsonObject(failure + ("user_agent" to JsonPrimitive("SyntheticBrowser/1.0"))).toString(), id, url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(matched, "b".repeat(32), url, 100))
    }

    @Test
    fun classicDocumentIsBoundToTheExactOriginalUrlAndNonce() {
        val source = "https://DOCS.YANDEX.RU:443/docs/view?url=synthetic%2fvalue&empty="
        val metadata = classicDocument(sourceUrl = source)
        val raw = response(listOf(cookie()), metadata)
        assertEquals(raw, OpenFluxBrowserProtocol.checkedResponse(raw, id, source, 1000))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, source.replace("%2f", "%2F"), 1000))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, source.replace(":443", ""), 1000))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, "b".repeat(32), source, 1000))
        val request = Json.parseToJsonElement(OpenFluxBrowserProtocol.helperRequest(id, source, Path.of("owned"))).jsonObject
        assertEquals(source, request.getValue("document_url").jsonPrimitive.content)
        val withAgent = JsonObject(Json.parseToJsonElement(raw).jsonObject +
            ("user_agent" to JsonPrimitive("SyntheticBrowser/1.0"))).toString()
        assertEquals(withAgent, OpenFluxBrowserProtocol.checkedResponse(withAgent, id, source, 1000))
    }

    @Test
    fun classicDocumentRequiresExactlyNineTypedFieldsAndCannotReplaceCookies() {
        val metadata = classicDocument()
        metadata.keys.forEach { key ->
            assertNull(checkedClassic(JsonObject(metadata - key)), "Missing $key")
            assertNull(checkedClassic(JsonObject(metadata + (key to Json.parseToJsonElement("null")))), "Null $key")
        }
        assertNull(checkedClassic(JsonObject(metadata + ("html" to JsonPrimitive("synthetic")))))
        listOf("source_url", "balancer_url", "token", "document_key", "file_type", "download_url", "title").forEach { key ->
            listOf("true", "123", "[]", "{}").forEach { value ->
                assertNull(checkedClassic(changedClassic(key, Json.parseToJsonElement(value))), "Wrong type for $key")
            }
        }
        listOf("null", "true", "123", "[]", "\"synthetic\"").forEach { value ->
            assertNull(checkedClassic(Json.parseToJsonElement(value)))
        }
        listOf(JsonPrimitive(false), JsonPrimitive("true"), JsonPrimitive(1)).forEach { value ->
            assertNull(checkedClassic(changedClassic("edit", value)))
        }
        listOf(JsonPrimitive("1000"), JsonPrimitive(true), JsonPrimitive(1000.0), JsonArray(emptyList())).forEach { value ->
            assertNull(checkedClassic(changedClassic("captured_at", value)))
        }
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(emptyList(), metadata), id, url, 1000))
        val failure = Json.parseToJsonElement(OpenFluxBrowserProtocol.failure(id)).jsonObject
        assertNull(OpenFluxBrowserProtocol.checkedResponse(JsonObject(failure + ("classic_document" to metadata)).toString(), id, url, 1000))
    }

    @Test
    fun classicDocumentHasAShortBoundedLifetime() {
        listOf(880L, 1000L, 1005L).forEach { assertNotNull(checkedClassic(classicDocument(capturedAt = it))) }
        listOf(879L, 1006L, Long.MIN_VALUE, Long.MAX_VALUE).forEach {
            assertNull(checkedClassic(classicDocument(capturedAt = it)))
        }
    }

    @Test
    fun classicDocumentAcceptsOnlyStrictYandexSubdomainUrls() {
        listOf("https://docs.yandex.ru", "https://storage.yandex.net:443", "https://A.B.yandex.ru").forEach {
            assertNotNull(checkedClassic(changedClassic("balancer_url", JsonPrimitive(it))))
        }
        assertNotNull(checkedClassic(changedClassic("download_url", JsonPrimitive("https://storage.yandex.net:443/file?token=synthetic"))))
        listOf(
            "http://docs.yandex.ru", "https://yandex.ru", "https://yandex.net", "https://notyandex.ru",
            "https://docs.yandex.ru.example", "https://user@docs.yandex.ru", "https://@docs.yandex.ru",
            "https://docs.yandex.ru:444", "https://docs.yandex.ru:0443", "https://docs.yandex.ru:",
            "https://docs.yandex.ru#", "https://docs.yandex.ru#fragment", "https://docs.yandex.ru.",
            "https://.yandex.ru", "https://a..yandex.ru", "https://-a.yandex.ru", "https://a-.yandex.ru",
            "https://${"a".repeat(64)}.yandex.ru", "https://\u044f.yandex.ru",
            "https://docs.yandex.ru\\path", "https://docs.yandex.ru/\t", "https://docs.yandex.ru/\u00a0",
            "https://docs.yandex.ru/\u0085", "https://docs.yandex.ru/%zz", "https://docs.yandex.ru/${"a".repeat(8192)}"
        ).forEach { value ->
            listOf("balancer_url", "download_url").forEach { field ->
                assertNull(checkedClassic(changedClassic(field, JsonPrimitive(value))), "Invalid $field")
            }
        }
        listOf("https://docs.yandex.ru/", "https://docs.yandex.ru?", "https://docs.yandex.ru?query=1", "https://docs.yandex.ru/path").forEach {
            assertNull(checkedClassic(changedClassic("balancer_url", JsonPrimitive(it))))
        }
    }

    @Test
    fun classicBalancerAllowsProviderUnderscoresOnlyInBoundedOrigins() {
        val longestHost = List(4) { "a".repeat(60) }.joinToString(".") + ".yandex.ru"
        assertEquals(253, longestHost.length)
        listOf("https://classic_a.yandex.ru", "https://_classic.yandex.net:443",
            "HTTPS://CLASSIC_.YANDEX.RU", "https://_.yandex.ru", "https://$longestHost").forEach { value ->
            val raw = response(listOf(cookie()), changedClassic("balancer_url", JsonPrimitive(value)))
            assertEquals(raw, OpenFluxBrowserProtocol.checkedResponse(raw, id, url, 1000))
        }
        assertNull(checkedClassic(changedClassic("download_url", JsonPrimitive("https://classic_a.yandex.ru/file"))))
        listOf("http://a_b.yandex.ru", "https://a_b.yandex.ru/", "https://a_b.yandex.ru/path",
            "https://a_b.yandex.ru?", "https://a_b.yandex.ru?q=1", "https://a_b.yandex.ru#",
            "https://a_b.yandex.ru#x", "https://user@a_b.yandex.ru", "https://a_b.yandex.ru:0443",
            "https://a_b.yandex.ru:", "https://a_b.yandex.ru:444", "https://a_b.yandex.ru.",
            "https://a_b..yandex.ru", "https://-a_b.yandex.ru", "https://a_b-.yandex.ru",
            "https://a_b.yandex.ru.example", "https://a%5fb.yandex.ru", "https://a+b.yandex.ru",
            "https://\u044f_b.yandex.ru", "https://${"_".repeat(64)}.yandex.ru", "https://a$longestHost").forEach {
            assertNull(checkedClassic(changedClassic("balancer_url", JsonPrimitive(it))))
        }
    }

    @Test
    fun classicDocumentAcceptsOpaqueInternalFileReferencesWithoutChangingThem() {
        val prefix = "http://localhost:12701/disk/"
        listOf(prefix + "x", prefix + "x?", prefix + "x?signature=synthetic%2fvalue&fragment=%23",
            prefix + "%D1%84?signature=%ff", prefix + "\u0444", prefix + "x%2Fy",
            prefix + "a".repeat(8192 - prefix.length)).forEach { value ->
            val raw = response(listOf(cookie()), changedClassic("download_url", JsonPrimitive(value)))
            assertEquals(raw, OpenFluxBrowserProtocol.checkedResponse(raw, id, url, 1000))
            assertNull(checkedClassic(changedClassic("balancer_url", JsonPrimitive(value))))
            assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, value, 1000))
        }
    }

    @Test
    fun classicDocumentRejectsInternalReferenceTraversalAndMalformedEncoding() {
        val prefix = "http://localhost:12701/disk/"
        listOf("HTTP://localhost:12701/disk/x", "http://LOCALHOST:12701/disk/x",
            "http://127.0.0.1:12701/disk/x", "http://localhost/disk/x", "http://localhost:12702/disk/x",
            "http://localhost:012701/disk/x", "http://user@localhost:12701/disk/x",
            "http://localhost:12701/other/x", prefix, prefix + "?x=1", prefix + "x#", prefix + "x#fragment",
            prefix + "x y", prefix + "x\\y", prefix + ".", prefix + "..", prefix + "a/../x",
            prefix + "%2e", prefix + "%2e%2e/x", prefix + "a/%2E%2E/x", prefix + "%252e%252e/x",
            prefix + "x%5Cy", prefix + "x%20y", prefix + "x%C2%A0y", prefix + "x%00y", prefix + "x%7Fy",
            prefix + "x%3Fy", prefix + "x%23y", prefix + "%ff", prefix + "%c0%af", prefix + "%ed%a0%80",
            prefix + "%C3%28", prefix + "%", prefix + "x?bad=%zz", prefix + "x?bad= ",
            prefix + "a".repeat(8193 - prefix.length)).forEach {
            assertNull(checkedClassic(changedClassic("download_url", JsonPrimitive(it))))
        }
    }

    @Test
    fun classicDocumentEnforcesAsciiAndUtf8FieldBudgets() {
        listOf("token" to 16_384, "document_key" to 512, "file_type" to 32).forEach { (key, maximum) ->
            assertNotNull(checkedClassic(changedClassic(key, JsonPrimitive("a".repeat(maximum)))))
            assertNull(checkedClassic(changedClassic(key, JsonPrimitive("a".repeat(maximum + 1)))))
            listOf("", "a b", "a\tb", "a\u007fb", "a\u0080b").forEach { value ->
                assertNull(checkedClassic(changedClassic(key, JsonPrimitive(value))))
            }
        }
        listOf(".", "..").forEach { assertNull(checkedClassic(changedClassic("document_key", JsonPrimitive(it)))) }
        listOf("a/b", "a+b", "a%b").forEach { assertNull(checkedClassic(changedClassic("file_type", JsonPrimitive(it)))) }
        listOf("", "\u00e9".repeat(512), "\ud83d\ude00".repeat(256)).forEach {
            assertNotNull(checkedClassic(changedClassic("title", JsonPrimitive(it))))
        }
        listOf("\u00e9".repeat(513), "a\nb", "a\u007fb", "a\u0085b", "\ud800").forEach {
            assertNull(checkedClassic(changedClassic("title", JsonPrimitive(it))))
        }
    }

    @Test
    fun classicDocumentAndCombinedReplyHaveSeparateSizeLimits() {
        val metadata = JsonObject(classicDocument() + mapOf(
            "token" to JsonPrimitive("a".repeat(16_384)),
            "download_url" to JsonPrimitive("https://docs.yandex.ru/${"a".repeat(8000)}"),
            "title" to JsonPrimitive("a".repeat(1024))
        ))
        val raw = response(listOf(cookie()), metadata)
        assertTrue(raw.toByteArray().size < OpenFluxBrowserProtocol.MAX_MESSAGE_BYTES)
        assertNull(OpenFluxBrowserProtocol.checkedResponse(raw, id, url, 1000))
        val bounded = JsonObject(metadata + ("download_url" to JsonPrimitive("https://docs.yandex.ru/${"a".repeat(5000)}")))
        assertNotNull(checkedClassic(bounded))
        val combined = response(List(3) { cookie(name = "anonymous$it", value = "a".repeat(4096)) }, bounded)
        assertTrue(combined.toByteArray().size > OpenFluxBrowserProtocol.MAX_MESSAGE_BYTES)
        assertNull(OpenFluxBrowserProtocol.checkedResponse(combined, id, url, 1000))
    }

    @Test
    fun rejectsAccountCookiesCaseInsensitively() {
        listOf("Session_id", "sessionid2", "YANDEX_LOGIN").forEach { assertNull(checked(cookie(name = it))) }
    }

    @Test
    fun rejectsWrongCookieScopeAndExpiredCookies() {
        listOf("yandex.ru.example", "example.org", "disk.yandex.ru", "..yandex.ru")
            .forEach { assertNull(checked(cookie(domain = it))) }
        assertNull(checked(cookie(path = "/other")))
        assertNull(checked(cookie(secure = false)))
        assertNull(checked(cookie(expires = 100)))
        assertNull(checked(cookie(expires = -1)))
    }

    @Test
    fun rejectsOversizedInvalidOrInjectedCookieValues() {
        assertNull(checked(cookie(value = "x".repeat(4097))))
        assertNull(checked(cookie(value = "a\r\nb")))
        assertNull(checked(cookie(value = "a;b")))
        assertNull(checked(cookie(name = "a=b")))
        assertNull(checked(cookie(name = "")))
    }

    @Test
    fun rejectsMismatchedIdsExtraFieldsAndTooManyCookies() {
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(listOf(cookie())), "b".repeat(32), url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(List(65) { cookie() }), id, url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(emptyList()), id, url, 100))
        val extended = Json.parseToJsonElement(response(listOf(cookie()))).jsonObject.toMutableMap()
        extended["unexpected"] = Json.parseToJsonElement("true")
        assertNull(OpenFluxBrowserProtocol.checkedResponse(JsonObject(extended).toString(), id, url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse("{invalid", id, url, 100))
    }

    @Test
    fun enforcesTotalMessageBudget() {
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(List(9) { cookie(value = "x".repeat(4000)) }), id, url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(List(7) { cookie(name = "anonymous$it", value = "x".repeat(4000)) }), id, url, 100))
        assertNull(checked(cookie(name = "x".repeat(129))))
    }

    @Test
    fun rejectsStringsInPlaceOfBooleanOrNumericCookieFields() {
        listOf("secure" to "true", "expires" to "0").forEach { (key, value) ->
            val wrongType = cookie().toMutableMap().also { it[key] = JsonPrimitive(value) }
            assertNull(checked(JsonObject(wrongType)))
        }
    }

    @Test
    fun rejectsDuplicateCookieIdentities() {
        assertNull(OpenFluxBrowserProtocol.checkedResponse(response(listOf(cookie(), cookie(domain = "yandex.ru"))), id, url, 100))
        assertNotNull(checked(cookie(domain = "")))
        assertNull(checked(cookie(domain = ".")))
    }

    @Test
    fun forwardsOnlyTheFixedFailureCategory() {
        val failure = OpenFluxBrowserProtocol.failure(id)
        assertEquals(failure, OpenFluxBrowserProtocol.checkedResponse(failure, id, url, 100))
        assertNull(OpenFluxBrowserProtocol.checkedResponse(failure.replace("verification_failed", "private-details"), id, url, 100))
    }

    @Test
    fun requestGateIsRollingAndAllowsFutureRefresh() {
        var now = 0L
        val gate = OpenFluxBrowserRequestGate { now }
        assertTrue(gate.allow(id))
        assertFalse(gate.allow(id))
        now = 1
        assertTrue(gate.allow("b".repeat(32)))
        assertFalse(gate.allow("c".repeat(32)))
        now = 299_999
        assertFalse(gate.allow("c".repeat(32)))
        now = 300_000
        assertTrue(gate.allow("c".repeat(32)))
        assertFalse(gate.allow("d".repeat(32)))
        now = 300_001
        assertTrue(gate.allow("d".repeat(32)))
    }

    @Test
    fun bridgeReturnsCookiesOnlyAfterItsPrivateDirectoryIsRemoved() = runBlocking {
        val helper = StubHelper(response(listOf(cookie())), alive = false)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap({ message ->
            if (message == "OpenFlux browser verification response accepted") {
                assertTrue(helper.inputClosed)
                assertFalse(Files.exists(checkNotNull(helper.directory)))
            }
            logs.add(message)
        }) { helper }.verify(id, url) { true }
        assertNotNull(OpenFluxBrowserProtocol.checkedResponse(result, id, url, 100))
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
        assertEquals(listOf("OpenFlux browser verification response accepted"), logs)
        assertFalse(logs.any { "synthetic" in it || url in it })
    }

    @Test
    fun bridgeForwardsClassicMetadataOnlyAfterItsPrivateDirectoryIsRemoved() = runBlocking {
        val raw = response(listOf(cookie()), classicDocument(capturedAt = Instant.now().epochSecond))
        val helper = StubHelper(raw, alive = false)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { helper }.verify(id, url) { true }
        assertEquals(raw, result)
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
        assertEquals(listOf("OpenFlux browser verification response accepted"), logs)
        assertFalse(logs.any { "synthetic" in it || url in it })
    }

    @Test
    fun bridgeReportsOnlyAFixedFailureForAnExplicitHelperFailureResponse() = runBlocking {
        val helper = StubHelper(OpenFluxBrowserProtocol.failure(id), alive = false)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { helper }.verify(id, url) { true }
        assertEquals(OpenFluxBrowserProtocol.failure(id), result)
        assertEquals(listOf("OpenFlux anonymous browser verification failed"), logs)
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
    }

    @Test
    fun bridgeReportsTheParentDeadlineEvenWhenTheHelperHasNotExited() = runBlocking {
        val helper = StubHelper("private-details", alive = true)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add, verificationTimeoutMs = 0) { helper }
            .verify(id, url) { true }
        assertEquals(OpenFluxBrowserProtocol.failure(id), result)
        assertEquals(listOf("OpenFlux browser verification timed out or was cancelled"), logs)
        assertFalse(helper.isAlive)
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
    }

    @Test
    fun bridgeDoesNotReportAcceptanceWhenTheNativeProcessIsAlreadyStopped() = runBlocking {
        val helper = StubHelper(response(listOf(cookie())), alive = false)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { helper }.verify(id, url) { false }
        assertEquals(OpenFluxBrowserProtocol.failure(id), result)
        assertTrue(logs.isEmpty())
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
    }

    @Test
    fun bridgeLogsOnlyAFixedCategoryWhenTheHelperResponseIsRejected() = runBlocking {
        val raw = response(listOf(cookie()), changedClassic("download_url", JsonPrimitive("private-details")))
        val helper = StubHelper(raw, alive = false)
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { helper }.verify(id, url) { true }
        assertEquals(OpenFluxBrowserProtocol.failure(id), result)
        assertEquals(listOf("OpenFlux browser verification response was rejected"), logs)
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
    }

    @Test
    fun cancellingVerificationClosesOnlyTheOwnedHelperAndRemovesItsData() = runBlocking {
        val owned = StubHelper("", alive = true)
        val unrelated = StubHelper("", alive = true)
        val job = launch { DesktopOpenFluxBrowserBootstrap({}) { owned }.verify(id, url) { true } }
        withTimeout(5_000) { owned.requestWritten.await() }
        job.cancelAndJoin()
        assertFalse(owned.isAlive)
        assertTrue(owned.inputClosed)
        assertFalse(Files.exists(checkNotNull(owned.directory)))
        assertTrue(unrelated.isAlive)
        assertFalse(unrelated.inputClosed)
    }

    @Test
    fun missingHelperFailsWithoutLeakingAnExceptionMessage() = runBlocking {
        val logs = mutableListOf<String>()
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { error("private-details") }.verify(id, url) { true }
        assertEquals(OpenFluxBrowserProtocol.failure(id), result)
        assertFalse(logs.any { "private-details" in it })
    }

    private class StubHelper(response: String, private var alive: Boolean) : Process() {
        val requestWritten = CompletableDeferred<Unit>()
        var directory: Path? = null
        var inputClosed = false
        private val responseStream = ByteArrayInputStream(response.toByteArray())
        private val requestStream = object : ByteArrayOutputStream() {
            override fun flush() {
                val request = Json.parseToJsonElement(toString(Charsets.UTF_8)).jsonObject
                directory = Path.of(request.getValue("user_data_dir").jsonPrimitive.content)
                Files.createDirectory(directory)
                Files.writeString(directory!!.resolve("synthetic-cache"), "offline-test-only")
                requestWritten.complete(Unit)
            }

            override fun close() { inputClosed = true; alive = false }
        }

        override fun getOutputStream() = requestStream
        override fun getInputStream() = responseStream
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = alive
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue() = 0
        override fun destroy() { alive = false }
    }

    private fun checked(cookie: JsonObject): String? = OpenFluxBrowserProtocol.checkedResponse(response(listOf(cookie)), id, url, 100)

    private fun checkedClassic(metadata: JsonElement): String? =
        OpenFluxBrowserProtocol.checkedResponse(response(listOf(cookie()), metadata), id, url, 1000)

    private fun changedClassic(key: String, value: JsonElement) = JsonObject(classicDocument() + (key to value))

    private fun classicDocument(sourceUrl: String = url, capturedAt: Long = 1000) = buildJsonObject {
        put("source_url", sourceUrl)
        put("captured_at", capturedAt)
        put("balancer_url", "https://docs.yandex.ru")
        put("token", "synthetic-token")
        put("document_key", "synthetic-key")
        put("file_type", "docx")
        put("download_url", "https://storage.yandex.net/synthetic?value=1")
        put("title", "Synthetic")
        put("edit", true)
    }

    private fun response(cookies: List<JsonObject>, metadata: JsonElement? = null) = buildJsonObject {
        put("id", id)
        put("cookies", JsonArray(cookies))
        metadata?.let { put("classic_document", it) }
    }.toString()

    private fun cookie(name: String = "anonymous", value: String = "synthetic", domain: String = ".yandex.ru",
        path: String = "/", secure: Boolean = true, expires: Long = 0) = buildJsonObject {
        put("name", name)
        put("value", value)
        put("domain", domain)
        put("path", path)
        put("secure", secure)
        put("expires", expires)
    }
}
