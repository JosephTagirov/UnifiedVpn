package org.olcbox.app.vpn.service

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxBrowserDiagnosticsTest {
    @Test
    fun tlsCallbacksMapOnlyToFixedCertificateFailureCategories() {
        listOf(
            OpenFluxBrowserDiagnosticStage.TlsNotYetValid,
            OpenFluxBrowserDiagnosticStage.TlsExpired,
            OpenFluxBrowserDiagnosticStage.TlsHostnameMismatch,
            OpenFluxBrowserDiagnosticStage.TlsUntrusted,
            OpenFluxBrowserDiagnosticStage.TlsDateInvalid,
            OpenFluxBrowserDiagnosticStage.TlsInvalid
        ).forEachIndexed { code, expected -> assertEquals(expected, openFluxBrowserTlsFailureStage(code)) }
        listOf(-1, 6, Int.MAX_VALUE).forEach {
            assertEquals(OpenFluxBrowserDiagnosticStage.TlsInvalid, openFluxBrowserTlsFailureStage(it))
        }
    }

    @Test
    fun webViewErrorsMapWithoutRetainingDescriptionsOrUrls() {
        mapOf(
            -2 to OpenFluxBrowserDiagnosticStage.WebViewHostLookup,
            -3 to OpenFluxBrowserDiagnosticStage.WebViewAuthentication,
            -4 to OpenFluxBrowserDiagnosticStage.WebViewAuthentication,
            -5 to OpenFluxBrowserDiagnosticStage.WebViewAuthentication,
            -6 to OpenFluxBrowserDiagnosticStage.WebViewConnect,
            -7 to OpenFluxBrowserDiagnosticStage.WebViewIo,
            -8 to OpenFluxBrowserDiagnosticStage.WebViewTimeout,
            -9 to OpenFluxBrowserDiagnosticStage.WebViewRedirectLoop,
            -10 to OpenFluxBrowserDiagnosticStage.WebViewScheme,
            -11 to OpenFluxBrowserDiagnosticStage.WebViewTlsHandshake,
            -12 to OpenFluxBrowserDiagnosticStage.WebViewBadUrl,
            -13 to OpenFluxBrowserDiagnosticStage.WebViewFile,
            -14 to OpenFluxBrowserDiagnosticStage.WebViewFile,
            -15 to OpenFluxBrowserDiagnosticStage.WebViewTooManyRequests,
            -16 to OpenFluxBrowserDiagnosticStage.WebViewUnsafeResource
        ).forEach { (code, expected) -> assertEquals(expected, openFluxBrowserWebViewFailureStage(code)) }
        listOf(Int.MIN_VALUE, -1, 0, 999).forEach {
            assertEquals(OpenFluxBrowserDiagnosticStage.WebViewOther, openFluxBrowserWebViewFailureStage(it))
        }
    }

    @Test
    fun mainFrameHttpStatusIsReducedToAnAllowlistedCategory() {
        mapOf(
            401 to OpenFluxBrowserHttpStatus.Unauthorized,
            403 to OpenFluxBrowserHttpStatus.Forbidden,
            404 to OpenFluxBrowserHttpStatus.NotFound,
            407 to OpenFluxBrowserHttpStatus.ProxyAuthentication,
            408 to OpenFluxBrowserHttpStatus.Timeout,
            429 to OpenFluxBrowserHttpStatus.RateLimited,
            400 to OpenFluxBrowserHttpStatus.ClientError,
            451 to OpenFluxBrowserHttpStatus.ClientError,
            500 to OpenFluxBrowserHttpStatus.ServerError,
            503 to OpenFluxBrowserHttpStatus.ServerError,
            200 to OpenFluxBrowserHttpStatus.Other,
            Int.MAX_VALUE to OpenFluxBrowserHttpStatus.Other
        ).forEach { (code, expected) -> assertEquals(expected, openFluxBrowserHttpStatus(code)) }
        assertEquals("OpenFlux: Browser main-frame HTTP response (status=forbidden)",
            openFluxBrowserHttpLogLine(openFluxBrowserHttpStatus(403)))
    }

    @Test
    fun diagnosticIpcRejectsUnknownCodesAndPrivateErrorText() {
        val privateText = "https://private.invalid/document?token=secret-cookie"
        val failure = openFluxBrowserReplyFailure(privateText, privateText, privateText, privateText, IllegalStateException(privateText))
        assertEquals(OpenFluxBrowserFailure.Failed, failure.reason)
        assertNull(failure.validationStage)
        assertEquals(OpenFluxBrowserDiagnosticStage.ReplyValidation, failure.diagnosticStage)
        assertNull(failure.httpStatus)
        assertEquals("Browser verification failed", failure.message)
        assertFalse(openFluxBrowserFailureLogLines(failure).joinToString().contains(privateText))
        listOf(null, "", privateText, "tls_untrusted extra", "forbidden\nsecret").forEach {
            assertNull(openFluxBrowserDiagnosticStage(it))
            assertNull(openFluxBrowserHttpStatus(it))
        }
    }

    @Test
    fun allDiagnosticCodesRoundTripAndHaveBoundedSafeSyntax() {
        OpenFluxBrowserDiagnosticStage.entries.forEach {
            assertEquals(it, openFluxBrowserDiagnosticStage(it.code))
            assertTrue(Regex("[a-z_]{1,32}").matches(it.code))
        }
        OpenFluxBrowserHttpStatus.entries.forEach {
            assertEquals(it, openFluxBrowserHttpStatus(it.code))
            assertTrue(Regex("[a-z_]{1,32}").matches(it.code))
        }
        OpenFluxBrowserTlsResource.entries.forEach {
            assertEquals(it, openFluxBrowserTlsResourceFromCode(it.code))
            assertTrue(Regex("[a-z_]{1,32}").matches(it.code))
        }
    }

    @Test
    fun tlsResourceCategorySurvivesIpcWithoutTheResourceUrlOrHostname() {
        mapOf(
            "https://docs.yandex.ru/docs/private?token=private-token" to OpenFluxBrowserTlsResource.Document,
            "https://hdrc.yandex.net/private?token=private-token" to OpenFluxBrowserTlsResource.Hdrc,
            "https://mdd.yandex.net/private?token=private-token" to OpenFluxBrowserTlsResource.Mdd
        ).forEach { (url, expected) ->
            val resource = openFluxBrowserTlsResource(url)
            val failure = openFluxBrowserReplyFailure("Failed", null, "tls_untrusted", null, null, resource.code)
            assertEquals(expected, failure.tlsResource)
            assertEquals(listOf("OpenFlux: Browser verification diagnostic (reason=Failed, stage=tls_untrusted, resource=${expected.code})"),
                openFluxBrowserFailureLogLines(failure))
            val logged = openFluxBrowserFailureLogLines(failure).joinToString()
            listOf(url, "yandex", "private-token", "https://").forEach { assertFalse(logged.contains(it)) }
        }
    }

    @Test
    fun tlsResourceIpcRejectsUnknownCategoriesAndPreservesLocalTypedValues() {
        listOf(null, "", "HDRC", "hdrc.yandex.net", "https://hdrc.yandex.net/private", "hdrc\nprivate").forEach { raw ->
            assertNull(openFluxBrowserTlsResourceFromCode(raw))
            val failure = openFluxBrowserReplyFailure("Failed", null, "tls_untrusted", null, null, raw)
            assertNull(failure.tlsResource)
            assertEquals(listOf("OpenFlux: Browser verification diagnostic (reason=Failed, stage=tls_untrusted)"),
                openFluxBrowserFailureLogLines(failure))
        }
        val local = OpenFluxBrowserException(OpenFluxBrowserFailure.Failed,
            diagnosticStage = OpenFluxBrowserDiagnosticStage.TlsExpired, tlsResource = OpenFluxBrowserTlsResource.Disk)
        val decoded = openFluxBrowserReplyFailure(null, null, null, null, local, "private-host")
        assertEquals(OpenFluxBrowserTlsResource.Disk, decoded.tlsResource)
    }

    @Test
    fun failedIpcReplyKeepsTlsAndHttpCategoriesInsteadOfMissingCookieParseFailure() {
        val failure = openFluxBrowserReplyFailure(
            "Failed", null, "tls_untrusted", "forbidden", IllegalStateException("missing cookies")
        )
        assertEquals(OpenFluxBrowserDiagnosticStage.TlsUntrusted, failure.diagnosticStage)
        assertEquals(OpenFluxBrowserHttpStatus.Forbidden, failure.httpStatus)
        assertEquals(listOf("OpenFlux: Browser verification diagnostic (reason=Failed, stage=tls_untrusted, http=forbidden)"),
            openFluxBrowserFailureLogLines(failure))
    }

    @Test
    fun localReplyValidationKeepsTypedMetadataStageAndAccountFailure() {
        val timestamp = OpenFluxBrowserException(OpenFluxBrowserFailure.Failed, OpenFluxBrowserValidationStage.ClassicTimestamp)
        val decoded = openFluxBrowserReplyFailure(null, null, null, null, timestamp)
        assertEquals(OpenFluxBrowserFailure.Failed, decoded.reason)
        assertEquals(OpenFluxBrowserValidationStage.ClassicTimestamp, decoded.validationStage)
        assertEquals(OpenFluxBrowserDiagnosticStage.ReplyValidation, decoded.diagnosticStage)
        assertTrue(openFluxBrowserFailureLogLines(decoded).first().contains("stage=classic_timestamp"))
        val account = assertFailsWith<OpenFluxBrowserException> {
            parseOpenFluxBrowserCookieHeaders(mapOf("docs.yandex.ru" to "session_id=private-cookie"))
        }
        val decodedAccount = openFluxBrowserReplyFailure(null, null, null, null, account)
        assertEquals(OpenFluxBrowserFailure.Account, decodedAccount.reason)
        assertFalse(openFluxBrowserFailureLogLines(decodedAccount).joinToString().contains("private-cookie"))
    }

    @Test
    fun metadataCaptureShapeFailuresAreNotGenericAfterJavascriptUnwrapping() {
        val missingFields = JsonPrimitive("{\"token\":\"private-token\"}").toString()
        listOf(null, "false", "{}", missingFields).forEach { capture ->
            val failure = assertFailsWith<OpenFluxBrowserException> {
                openFluxBrowserCapturedDocument(capture, "https://docs.yandex.ru/docs/synthetic")
            }
            assertEquals(OpenFluxBrowserValidationStage.ClassicShape, failure.validationStage)
            assertFalse(openFluxBrowserFailureLogLines(failure).joinToString().contains("private-token"))
        }
        val oversized = assertFailsWith<OpenFluxBrowserException> {
            openFluxBrowserCapturedDocument("x".repeat(2 * OPENFLUX_CLASSIC_DOCUMENT_MAX_BYTES + 3), "https://docs.yandex.ru/docs/synthetic")
        }
        assertEquals(OpenFluxBrowserValidationStage.ClassicSize, oversized.validationStage)
    }
}
