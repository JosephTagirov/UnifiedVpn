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
    fun initialNavigationWaitsOnlyWhileNoUrlOrNavigationHasBeenObserved() {
        listOf(null, "").forEach { url ->
            assertEquals(OpenFluxBrowserNavigationPoll.AwaitInitialNavigation, openFluxBrowserNavigationPoll(url, false))
            assertEquals(OpenFluxBrowserNavigationPoll.Reject, openFluxBrowserNavigationPoll(url, true))
        }
    }

    @Test
    fun permittedNavigationStartMayPrecedeCurrentUrlButObservedUrlCannotRegress() {
        // onPageStarted validates the destination without setting navigationObserved.
        assertTrue(isOpenFluxBrowserNavigationAllowed(URL))
        assertEquals(OpenFluxBrowserNavigationPoll.AwaitInitialNavigation, openFluxBrowserNavigationPoll(null, false))
        assertEquals(OpenFluxBrowserNavigationPoll.InspectDocument, openFluxBrowserNavigationPoll(URL, false))
        // Inspection or onPageFinished ends the initial missing-URL allowance permanently.
        assertEquals(OpenFluxBrowserNavigationPoll.Reject, openFluxBrowserNavigationPoll(null, true))
    }

    @Test
    fun navigationPollingInspectsOnlyAllowedDocuments() {
        listOf(false, true).forEach { observed ->
            listOf(URL, "https://disk.yandex.ru/i/synthetic").forEach { url ->
                assertEquals(OpenFluxBrowserNavigationPoll.InspectDocument, openFluxBrowserNavigationPoll(url, observed))
            }
        }
    }

    @Test
    fun initialNavigationNeverAllowsBlankPagesOrAnActualDisallowedUrl() {
        listOf(false, true).forEach { observed ->
            listOf(" ", "about:blank", "javascript:alert(1)", "file:///private", "https://passport.yandex.ru/",
                "http://docs.yandex.ru/", "https://docs.yandex.ru:444/", "https://docs.yandex.ru.attacker.invalid/"
            ).forEach { url ->
                assertEquals(OpenFluxBrowserNavigationPoll.Reject, openFluxBrowserNavigationPoll(url, observed))
            }
        }
    }

    @Test
    fun replyCarriesExactValidatedBrowserIdentityWithoutChangingCookieScope() {
        val cookies = parseOpenFluxBrowserCookies("proof=synthetic", "https://docs.yandex.ru/")
        val session = OpenFluxBrowserSession(cookies, "SyntheticBrowser/1.0")
        val reply = Json.parseToJsonElement(openFluxBrowserResponse(ID, session.cookies, session.userAgent)).jsonObject
        assertEquals("SyntheticBrowser/1.0", reply.getValue("user_agent").jsonPrimitive.content)
        assertEquals("docs.yandex.ru", reply.getValue("cookies").jsonArray.single().jsonObject.getValue("domain").jsonPrimitive.content)
        assertFalse(openFluxBrowserResponse(ID, cookies).contains("user_agent"))
        assertFalse(session.toString().contains("synthetic"))
        listOf("", " leading", "trailing ", "a\r\nb", "a\tb", "a\u0000b", "a\u007fb", "a\u0080b", "a".repeat(1025)).forEach { agent ->
            assertFalse(isOpenFluxBrowserUserAgentValid(agent))
            assertFailsWith<IllegalArgumentException> { OpenFluxBrowserSession(cookies, agent) }
            assertFailsWith<IllegalArgumentException> { openFluxBrowserResponse(ID, cookies, agent) }
        }
        assertTrue(isOpenFluxBrowserUserAgentValid("a".repeat(1024)))
        assertFailsWith<IllegalArgumentException> { openFluxBrowserResponse(ID, null, session.userAgent) }
    }

    @Test
    fun tlsDiagnosticsReturnOnlyFixedResourceCategories() {
        mapOf(
            "https://docs.yandex.ru/private?token=secret" to OpenFluxBrowserTlsResource.Document,
            "https://disk.yandex.ru/private" to OpenFluxBrowserTlsResource.Disk,
            "https://HDRC.yandex.net/private" to OpenFluxBrowserTlsResource.Hdrc,
            "https://mdd.yandex.net/private" to OpenFluxBrowserTlsResource.Mdd,
            "https://static.yastatic.net/private" to OpenFluxBrowserTlsResource.Static,
            "https://other.yandex.net/private" to OpenFluxBrowserTlsResource.Provider
        ).forEach { (url, category) ->
            assertEquals(category, openFluxBrowserTlsResource(url))
        }
        listOf(null, "", "https://private.invalid/", "https://hdrc.yandex.net.attacker.invalid/",
            "https://passport.yandex.ru/", "http://hdrc.yandex.net/", "https://user@hdrc.yandex.net/",
            "https://hdrc.yandex.net:444/", "https://hdrc.yandex.net/\nprivate").forEach {
            assertEquals(OpenFluxBrowserTlsResource.Unapproved, openFluxBrowserTlsResource(it))
        }
    }

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
        assertEquals(OpenFluxBrowserPageState.FileViewer, openFluxBrowserPageState("\"file-viewer\""))
        assertEquals(OpenFluxBrowserPageState.Other, openFluxBrowserPageState("\"other\""))
        listOf(null, "", "true", "\"unknown\"", "private-cookie-value", "\"config-private-value\"").forEach {
            assertEquals(OpenFluxBrowserPageState.Unknown, openFluxBrowserPageState(it))
        }
    }

    @Test
    fun onlyACompletedFileViewerGetsTheActionableDocumentError() {
        OpenFluxBrowserPageState.entries.forEach { state ->
            assertEquals(OpenFluxBrowserFailure.Timeout, openFluxBrowserTimeoutFailure(state, false))
            assertEquals(
                if (state == OpenFluxBrowserPageState.FileViewer) OpenFluxBrowserFailure.DocumentViewer else OpenFluxBrowserFailure.Timeout,
                openFluxBrowserTimeoutFailure(state, true)
            )
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
