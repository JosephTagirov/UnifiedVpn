package org.olcbox.app.vpn

import org.olcbox.app.data.model.ClientRoutingPolicy
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.*

class ClientRoutingPolicyFallbackTest {
    @Test fun oldMissingAndInvalidPoliciesUseUnchangedBuiltInRules() {
        assertEquals(869, ClientDirectPolicy.domainRules.size)
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

