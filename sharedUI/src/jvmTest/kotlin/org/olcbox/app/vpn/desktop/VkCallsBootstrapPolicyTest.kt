package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.*
import kotlin.test.*

class VkCallsBootstrapPolicyTest {
    private val subscription = "https://example.com/private-subscription"
    private val direct = OpenFluxProfileConfig(documentUrl="tcp://151.243.209.234:50010",
        encryptionKey="ab".repeat(32),version=2,transport="direct",wireCodec=OpenFluxProfileConfig.CODEC_SESSION)
    private fun entry(url: String?,config: OpenFluxProfileConfig = direct) =
        LocationEntry(storageId="support",subscriptionUrl=url,vpnProfile=VpnProfileConfig(
            type="openflux",rawConfig=config.toJson()))

    @Test fun onlyTheSameHttpsSubscriptionMayProvideSupport() {
        val expected = entry(subscription)
        assertEquals(expected.profile,VkCallsBootstrap.findProfile(subscription,listOf(entry("https://other.example/sub"),expected)))
        assertNull(VkCallsBootstrap.findProfile(subscription,listOf(entry("https://other.example/sub"))))
        assertNull(VkCallsBootstrap.findProfile(null,listOf(entry(null))))
        assertNull(VkCallsBootstrap.findProfile("http://example.com/sub",listOf(entry("http://example.com/sub"))))
    }

    @Test fun invalidPrivateOrBrowserCarriersCannotBecomeSupport() {
        for (bad in listOf(direct.copy(documentUrl="tcp://127.0.0.1:50010"),
            direct.copy(encryptionKey=""),direct.copy(transport="telemost",documentUrl="https://telemost.yandex.ru/j/12345678901234"))) {
            assertNull(VkCallsBootstrap.findProfile(subscription,listOf(entry(subscription,bad))))
        }
    }
}
