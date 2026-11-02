package org.olcbox.app.data.share
import kotlin.test.*
class SelectionImportLinkTest {
 private val token="a".repeat(43)
 private val source="https://panel.meesharr.space/vpn-control/bundle/$token"
 @Test fun acceptsOnlyTheOwnerSelectedSetEndpoint(){
  assertEquals(source,SelectionImportLink.parse("unifiedvpn://import?url=https%3A%2F%2Fpanel.meesharr.space%2Fvpn-control%2Fbundle%2F$token"))
 }
 @Test fun rejectsOtherOriginsCredentialsPathsAndAmbiguousArguments(){
  for(url in listOf("http://panel.meesharr.space/vpn-control/bundle/$token","https://evil.test/vpn-control/bundle/$token","https://panel.meesharr.space:8443/vpn-control/bundle/$token","https://user:pass@panel.meesharr.space/vpn-control/bundle/$token","https://panel.meesharr.space/vpn-control/admin/","https://panel.meesharr.space/vpn-control/bundle/short")){
   assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$url"))
  }
  assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$source&url=$source"))
  assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$source&extra=1"))
  assertNull(SelectionImportLink.parse("unifiedvpn://other?url=$source"))
 }
}
