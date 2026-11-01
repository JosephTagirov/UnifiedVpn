package org.olcbox.app.ui.components
import kotlin.test.*
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.components.profileBadge
class ClientAccessTest {
 @Test fun termsDistinguishUnknownUnlimitedExpiredAndDate(){
  assertNull(SubscriptionMetadata().accessTerm(1))
  assertEquals("Бессрочно",SubscriptionMetadata(accessExpires=0).accessTerm(1))
  assertEquals("Срок истёк",SubscriptionMetadata(accessExpires=1).accessTerm(2))
  assertEquals("до 02.01.70",SubscriptionMetadata(accessExpires=86400).accessTerm(1))
 }
 @Test fun portalCannotOpenAdminOrForeignSite(){
  assertNull(SubscriptionMetadata(clientPortalUrl="https://evil.test/").safePortalUrl())
  assertNull(SubscriptionMetadata(clientPortalUrl="https://panel.meesharr.space/vpn-control/admin/").safePortalUrl())
  assertNotNull(SubscriptionMetadata(clientPortalUrl="https://panel.meesharr.space/vpn-control/client/"+"a".repeat(43)).safePortalUrl())
 }
 @Test fun vkBadgeUsesProviderRatherThanInternalTransportName(){
  assertEquals("VK",profileBadge(LocationItem("x","VK",config=LocationConfig(bypassProvider="vkcalls"))))
 }
}
