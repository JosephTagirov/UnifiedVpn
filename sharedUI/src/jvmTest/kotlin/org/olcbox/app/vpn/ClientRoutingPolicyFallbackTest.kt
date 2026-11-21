package org.olcbox.app.vpn

import org.olcbox.app.data.model.ClientRoutingPolicy
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.*

class ClientRoutingPolicyFallbackTest {
    @Test fun oldMissingAndInvalidPoliciesUseCurrentBuiltInRules() {
        assertTrue(ClientDirectPolicy.domainRules.containsAll(listOf(
            "domain:cdn.yandex.net", "full:ipv4-internet.yandex.net", "full:ipv6-internet.yandex.net"
        )))
        assertEquals(ClientDirectPolicy.domainRules, ClientDirectPolicy.effectiveDomains(null))
        assertEquals(ClientDirectPolicy.domainRules, ClientDirectPolicy.effectiveDomains(VpnProfileConfig()))
        assertEquals(ClientDirectPolicy.domainRules, ClientDirectPolicy.effectiveDomains(
            VpnProfileConfig(routingPolicy = ClientRoutingPolicy(version = 2))))
    }

    @Test fun validPolicyReplacesBuiltInRules() {
        val rules = listOf("full:api.example.test")
        assertEquals(rules, ClientDirectPolicy.effectiveDomains(VpnProfileConfig(
            routingPolicy = ClientRoutingPolicy(revision = "test-r2", directDomains = rules))))
    }
}

