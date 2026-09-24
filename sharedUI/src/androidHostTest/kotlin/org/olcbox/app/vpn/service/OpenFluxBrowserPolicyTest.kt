package org.olcbox.app.vpn.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxBrowserPolicyTest {
    @Test
    fun redirectCookiesStayBoundToTwoIndependentDocumentHosts() {
        val cookies = parseOpenFluxBrowserCookieHeaders(mapOf(
            "disk.yandex.ru" to "proof=disk-value", "docs.yandex.ru" to "proof=docs-value"
        ))
        assertEquals(listOf("disk.yandex.ru", "docs.yandex.ru"), cookies.map { it.domain })
        assertEquals(listOf("disk-value", "docs-value"), cookies.map { it.value })
        assertEquals(1, parseOpenFluxBrowserCookieHeaders(mapOf("disk.yandex.ru" to "", "docs.yandex.ru" to "proof=value")).size)
    }

    @Test
    fun redirectCookiesRejectUnapprovedHostsAccountsAndCombinedLimits() {
        listOf(mapOf("yandex.ru" to "proof=value"), mapOf("passport.yandex.ru" to "proof=value"),
            mapOf("disk.yandex.ru" to ""), mapOf("docs.yandex.ru" to "session_id=private"),
            mapOf("disk.yandex.ru" to (1..20).joinToString(";") { "p$it=value" },
                "docs.yandex.ru" to (1..20).joinToString(";") { "p$it=value" }),
            mapOf("disk.yandex.ru" to "x".repeat(9000), "docs.yandex.ru" to "x".repeat(9000))
        ).forEach { headers ->
            assertFailsWith<OpenFluxBrowserException> { parseOpenFluxBrowserCookieHeaders(headers) }
        }
    }

    @Test
    fun desktopAgentRetainsInstalledChromiumVersionWithoutDeviceInformation() {
        val agent = openFluxBrowserDesktopUserAgent(
            "Mozilla/5.0 (Linux; Android 13; PRIVATE_DEVICE; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.12 Mobile Safari/537.36"
        )!!
        assertTrue(agent.contains("Chrome/153.0.8010.12"))
        listOf("Mobile", "Android", "PRIVATE_DEVICE", "; wv", "Version/4.0").forEach {
            assertFalse(agent.contains(it))
        }
    }

    @Test
    fun desktopAgentRefusesMalformedOrAmbiguousVersionInformation() {
        listOf("", "Chrome/153", "Chrome/153.0.0.0suffix", "Chrome/153.0.0.0\r\nprivate",
            "Chrome/153.0.0.0 Chrome/152.0.0.0", "x".repeat(2049)).forEach {
            assertNull(openFluxBrowserDesktopUserAgent(it))
        }
    }

    @Test
    fun onlyExactNativeNonceRequestsAreAccepted() {
        assertEquals(ID, openFluxBrowserRequestId("OPENFLUX_BROWSER_VERIFY $ID"))
        listOf("prefix OPENFLUX_BROWSER_VERIFY $ID", "OPENFLUX_BROWSER_VERIFY $ID secret", "OPENFLUX_BROWSER_VERIFY ${"A".repeat(32)}", "OPENFLUX_BROWSER_VERIFY short")
            .forEach { assertNull(openFluxBrowserRequestId(it)) }
    }

    @Test
    fun pageCheckpointsNeverReturnUntrustedPageContent() {
        assertEquals(OpenFluxBrowserPageState.Loading, openFluxBrowserPageState("\"loading\""))
        assertEquals(OpenFluxBrowserPageState.Config, openFluxBrowserPageState("\"config\""))
        assertEquals(OpenFluxBrowserPageState.Verification, openFluxBrowserPageState("\"verification\""))
        assertEquals(OpenFluxBrowserPageState.Other, openFluxBrowserPageState("\"other\""))
        listOf(null, "", "true", "\"unknown\"", "private-cookie-value", "\"config-private-value\"").forEach {
            assertEquals(OpenFluxBrowserPageState.Unknown, openFluxBrowserPageState(it))
        }
    }

    @Test
    fun navigationIsLimitedToProviderDocumentsOverStrictHttps() {
        listOf(URL, "https://disk.yandex.ru/i/synthetic", "https://docs.yandex.ru:443/docs/test").forEach {
            assertTrue(isOpenFluxBrowserNavigationAllowed(it))
        }
        listOf("http://docs.yandex.ru/docs/test", "https://docs.yandex.ru:444/docs/test", "https://docs.yandex.ru.attacker.invalid/", "https://user@docs.yandex.ru/docs/test", "https://passport.yandex.ru/", "javascript:alert(1)", "file:///private", "https://127.0.0.1/", "https://docs.yandex.ru./", "https://docs.yandex.ru\\@attacker.invalid/").forEach {
            assertFalse(isOpenFluxBrowserNavigationAllowed(it), it)
        }
    }

    @Test
    fun resourceRootsUseDnsLabelBoundariesAndExcludeLogin() {
        listOf("https://yastatic.net/check.js", "https://static.yandex.net/check.js", "https://smartcaptcha.yandexcloud.net/check", URL).forEach {
            assertTrue(isOpenFluxBrowserResourceAllowed(it))
        }
        listOf("https://fake-yandex.ru/check", "https://yandex.net.attacker.invalid/check", "https://passport.yandex.ru/auth", "https://oauth.yandex.ru/auth", "https://sub.passport.yandex.ru/", "wss://docs.yandex.ru/socket", "http://yastatic.net/check.js").forEach {
            assertFalse(isOpenFluxBrowserResourceAllowed(it), it)
        }
    }

    @Test
    fun cookiesRemainHostOnlySecureAndSessionScoped() {
        val cookies = parseOpenFluxBrowserCookies("spravka=synthetic-proof; yandexuid=synthetic-anonymous", URL)
        val response = Json.parseToJsonElement(openFluxBrowserResponse(ID, cookies)).jsonObject
        assertEquals(ID, response.getValue("id").jsonPrimitive.content)
        val cookie = response.getValue("cookies").jsonArray[0].jsonObject
        assertEquals("docs.yandex.ru", cookie.getValue("domain").jsonPrimitive.content)
        assertEquals("/", cookie.getValue("path").jsonPrimitive.content)
        assertEquals("true", cookie.getValue("secure").jsonPrimitive.content)
        assertEquals("0", cookie.getValue("expires").jsonPrimitive.content)
        assertFalse(cookies.toString().contains("synthetic-proof"))
        assertFalse(openFluxBrowserResponse(ID, cookies).contains('\n'))
    }

    @Test
    fun accountCookiesAreRejectedEvenWhenEmptyOrMixedCase() {
        listOf("Session_id=value", "sessionid2=", "YANDEX_LOGIN=person").forEach { cookie ->
            val failure = assertFailsWith<OpenFluxBrowserException> {
                parseOpenFluxBrowserCookies("spravka=synthetic; $cookie", URL)
            }
            assertEquals(OpenFluxBrowserFailure.Account, failure.reason)
            assertFalse(failure.message.orEmpty().contains(cookie))
        }
    }

    @Test
    fun malformedAndOversizedCookieHeadersFailClosed() {
        listOf("", "missing-separator", "a=b\r\nInjected: x", "a=hello world", "a=one; a=two", "a=${"x".repeat(4097)}", (1..33).joinToString("; ") { "cookie$it=value" }).forEach {
            assertFailsWith<OpenFluxBrowserException> { parseOpenFluxBrowserCookies(it, URL) }
        }
        assertFailsWith<OpenFluxBrowserException> { parseOpenFluxBrowserCookies("a=b", "https://attacker.invalid/") }
    }

    @Test
    fun limitsTwoRequestsInRollingFiveMinutesRatherThanLifetime() {
        var clock = 0L
        val limiter = OpenFluxBrowserRequestLimiter { clock }
        assertTrue(limiter.admit())
        clock = 100_000
        assertTrue(limiter.admit())
        assertFalse(limiter.admit())
        clock = 299_999
        assertFalse(limiter.admit())
        clock = 300_000
        assertTrue(limiter.admit())
        assertFalse(limiter.admit())
        clock = 400_000
        assertTrue(limiter.admit())
    }

    @Test
    fun failureResponseContainsOnlyNonceAndFixedCategory() {
        assertEquals("{\"id\":\"$ID\",\"error\":\"verification_failed\"}", openFluxBrowserResponse(ID, null))
        assertFailsWith<IllegalArgumentException> { openFluxBrowserResponse("unsafe\nnonce", null) }
    }

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef"
        const val URL = "https://docs.yandex.ru/docs/view?url=synthetic-browser-test"
    }
}
