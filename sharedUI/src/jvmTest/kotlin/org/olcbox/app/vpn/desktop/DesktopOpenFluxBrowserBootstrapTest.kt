package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        val result = DesktopOpenFluxBrowserBootstrap(logs::add) { helper }.verify(id, url) { true }
        assertNotNull(OpenFluxBrowserProtocol.checkedResponse(result, id, url, 100))
        assertTrue(helper.inputClosed)
        assertFalse(Files.exists(checkNotNull(helper.directory)))
        assertFalse(logs.any { "synthetic" in it || url in it })
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

    private fun response(cookies: List<JsonObject>) = buildJsonObject {
        put("id", id)
        put("cookies", JsonArray(cookies))
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
