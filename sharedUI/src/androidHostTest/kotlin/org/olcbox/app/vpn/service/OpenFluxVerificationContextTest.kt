package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OpenFluxVerificationContextTest {
    @Test
    fun leaseIsBoundToTheExactDocumentAndConsumedOnce() {
        val session = session()
        val lease = OpenFluxBrowserLease(session, URL, { true }, { 0 })
        assertSame(session, lease.consume(URL))
        assertEquals(OpenFluxBrowserFailure.Expired, assertFailsWith<OpenFluxBrowserException> { lease.consume(URL) }.reason)
        val wrongDocument = OpenFluxBrowserLease(session, URL, { true }, { 0 })
        assertFailsWith<OpenFluxBrowserException> { wrongDocument.consume("$URL&other=1") }
        assertFailsWith<OpenFluxBrowserException> { wrongDocument.consume(URL) }
    }

    @Test
    fun leaseRejectsExpiryClockRegressionAndInvalidatedOwnership() {
        var clock = 0L
        var current = true
        fun lease() = OpenFluxBrowserLease(session(), URL, { current }, { clock })
        val timed = lease()
        clock = 119_999
        timed.requireCurrent()
        clock = 120_000
        assertFailsWith<OpenFluxBrowserException> { timed.consume(URL) }
        clock = 0
        val regression = lease()
        clock = -1
        assertFailsWith<OpenFluxBrowserException> { regression.consume(URL) }
        clock = 0
        val stale = lease()
        current = false
        assertFailsWith<OpenFluxBrowserException> { stale.consume(URL) }
        current = true
        assertFailsWith<OpenFluxBrowserException> { stale.consume(URL) }
        val cancelled = lease()
        cancelled.invalidate()
        assertFailsWith<OpenFluxBrowserException> { cancelled.consume(URL) }
    }

    private fun session() = OpenFluxBrowserSession(parseOpenFluxBrowserCookies("proof=synthetic", URL), "SyntheticBrowser/1.0")

    private companion object {
        const val URL = "https://docs.yandex.ru/docs/view?url=synthetic"
    }
}
