package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class OlcRtcDnsEndpointTest {
    @Test
    fun addsDefaultPortToIpv4AndHostname() {
        assertEquals("1.1.1.1:53", normalizeOlcRtcDnsEndpoint("1.1.1.1"))
        assertEquals("dns.example:53", normalizeOlcRtcDnsEndpoint(" dns.example "))
    }

    @Test
    fun preservesExplicitPort() {
        assertEquals("1.1.1.1:5353", normalizeOlcRtcDnsEndpoint("1.1.1.1:5353"))
        assertEquals("dns.example:5353", normalizeOlcRtcDnsEndpoint("dns.example:5353"))
    }

    @Test
    fun formatsIpv6Endpoints() {
        assertEquals("[2606:4700:4700::1111]:53", normalizeOlcRtcDnsEndpoint("2606:4700:4700::1111"))
        assertEquals("[2606:4700:4700::1111]:53", normalizeOlcRtcDnsEndpoint("[2606:4700:4700::1111]"))
        assertEquals("[2606:4700:4700::1111]:5353", normalizeOlcRtcDnsEndpoint("[2606:4700:4700::1111]:5353"))
    }

    @Test
    fun preservesBlankValue() {
        assertEquals("", normalizeOlcRtcDnsEndpoint("  "))
    }

    @Test
    fun selectsConfiguredDnsBeforeFallback() {
        assertEquals(
            "9.9.9.9:53",
            selectOlcRtcDnsEndpoint(" 9.9.9.9 ", "1.1.1.1:53")
        )
    }

    @Test
    fun selectsStableFallbackWhenDnsIsBlank() {
        assertEquals(
            "1.1.1.1:53",
            selectOlcRtcDnsEndpoint(" ", "1.1.1.1")
        )
        assertEquals(
            "[2606:4700:4700::1111]:53",
            selectOlcRtcDnsEndpoint("", "2606:4700:4700::1111")
        )
    }
}
