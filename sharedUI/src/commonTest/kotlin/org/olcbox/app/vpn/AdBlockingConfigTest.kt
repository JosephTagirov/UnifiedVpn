package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import kotlin.test.*

class AdBlockingConfigTest {
    private fun config(preserve: Boolean = false) = Json.parseToJsonElement(AdBlockingConfig.build(
        "127.0.0.1", 10808, "u", "p", "127.0.0.1", 10809, "C:/private/ads.json",
        preserveUpstreamDns = preserve
    )).jsonObject

    @Test fun allowedTrafficHasOnlyTheExistingTunnelAsAnExit() {
        val config = config()
        val outbounds = config.getValue("outbounds").jsonArray
        assertEquals(1, outbounds.size)
        assertEquals("socks", outbounds.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(AdBlockingConfig.UPSTREAM, config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        val dns = config.getValue("dns").jsonObject
        assertEquals(AdBlockingConfig.UPSTREAM, dns.getValue("servers").jsonArray.single().jsonObject.getValue("detour").jsonPrimitive.content)
        assertEquals("NXDOMAIN", dns.getValue("rules").jsonArray.single().jsonObject.getValue("rcode").jsonPrimitive.content)
    }

    @Test fun wireGuardRetainsItsProfileDnsResolverAndIpv6Support() {
        val dns = config(true).getValue("dns").jsonObject
        assertEquals("tcp", dns.getValue("servers").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(53, dns.getValue("servers").jsonArray.single().jsonObject.getValue("server_port").jsonPrimitive.int)
        assertEquals("prefer_ipv4", dns.getValue("strategy").jsonPrimitive.content)
    }

    @Test fun rejectsRecursiveSocksConfiguration() {
        assertFailsWith<IllegalArgumentException> { AdBlockingConfig.build("127.0.0.1", 10808,"","","127.0.0.1",10808,"ads.json") }
    }
}
