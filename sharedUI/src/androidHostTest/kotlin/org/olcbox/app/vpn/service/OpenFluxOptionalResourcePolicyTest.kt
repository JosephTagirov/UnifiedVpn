package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxOptionalResourcePolicyTest {
    @Test
    fun onlyExactHttpsResourceHostsCanRemainBlockedWhileDocumentVerificationContinues() {
        listOf("https://hdrc.yandex.net/resource", "https://mdd.yandex.net/resource",
            "https://HDRC.YANDEX.NET:443/resource?synthetic=1").forEach { resource ->
            listOf(DOCUMENT, "https://disk.yandex.ru/edit/synthetic").forEach { mainFrame ->
                assertTrue(canContinueAfterBlockingOpenFluxTlsResource(resource, mainFrame))
            }
        }
    }

    @Test
    fun mainDocumentAndUnrecognizedTlsFailuresStillAbortVerification() {
        listOf(null, "", DOCUMENT, "https://disk.yandex.ru/edit/synthetic",
            "https://yastatic.net/resource", "https://yandex.ru/resource",
            "https://other.yandex.net/resource", "https://private.invalid/resource").forEach {
            assertFalse(canContinueAfterBlockingOpenFluxTlsResource(it, DOCUMENT))
        }
    }

    @Test
    fun resourcePolicyRequiresAnAllowedMainFrameAndCannotAllowResourceNavigation() {
        listOf(null, "", "about:blank", "https://hdrc.yandex.net/resource", "https://mdd.yandex.net/resource",
            "http://docs.yandex.ru/docs/synthetic", "https://docs.yandex.ru/auth",
            "https://private.invalid/document").forEach { mainFrame ->
            assertFalse(canContinueAfterBlockingOpenFluxTlsResource("https://hdrc.yandex.net/resource", mainFrame))
        }
    }

    @Test
    fun malformedAuthorityLookalikesAndUnexpectedSchemesRemainFatal() {
        listOf(
            "http://hdrc.yandex.net/resource", "wss://hdrc.yandex.net/resource",
            "https://hdrc.yandex.net:444/resource", "https://hdrc.yandex.net:0443/resource",
            "https://user@hdrc.yandex.net/resource", "https://@hdrc.yandex.net/resource",
            "https://hdrc.yandex.net@private.invalid/resource", "https://hdrc.yandex.net.private.invalid/resource",
            "https://sub.hdrc.yandex.net/resource", "https://hdrc.yandex.net./resource",
            "https://hdrc.yandex.net/resource#fragment", "https://hdrc.yandex.net/resource#",
            "https://hdrc%2eyandex.net/resource", "https://hdrc.yandex.net\\@private.invalid/resource",
            "https://hdrc.yandex.net/ private", "https://hdrc.yandex.net/\nprivate",
            "//hdrc.yandex.net/resource", "hdrc.yandex.net/resource", "https://[::1]/resource", "https://["
        ).forEach { resource ->
            assertFalse(canContinueAfterBlockingOpenFluxTlsResource(resource, DOCUMENT), resource)
        }
    }

    @Test
    fun blockedResourceDiagnosticsContainOnlyFixedTlsAndResourceCodes() {
        assertEquals("OpenFlux: Browser resource blocked (stage=tls_untrusted, resource=hdrc)",
            openFluxBrowserBlockedTlsLogLine("tls_untrusted", "hdrc"))
        assertEquals("OpenFlux: Browser resource blocked (stage=tls_hostname_mismatch, resource=mdd)",
            openFluxBrowserBlockedTlsLogLine("tls_hostname_mismatch", "mdd"))
        listOf(null, "", "TLS_UNTRUSTED", "tls_untrusted private-token", "page_inspection", "https://private.invalid").forEach {
            assertNull(openFluxBrowserBlockedTlsLogLine(it, "hdrc"))
        }
        listOf(null, "", "document", "provider", "unapproved", "hdrc.yandex.net", "hdrc\nprivate-cookie").forEach {
            assertNull(openFluxBrowserBlockedTlsLogLine("tls_untrusted", it))
        }
    }

    private companion object {
        const val DOCUMENT = "https://docs.yandex.ru/docs/synthetic"
    }
}
