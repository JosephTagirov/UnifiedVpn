package org.olcbox.app.data.share
import io.ktor.http.encodeURLParameter
import kotlin.test.*
class SelectionImportLinkTest {
 private val token="a".repeat(43)
 private val source="https://panel.meesharr.space/vpn-control/bundle/$token"
 @Test fun acceptsOldOwnerSelectedSetEndpoint(){
  assertEquals(source,SelectionImportLink.parse("unifiedvpn://import?url=https%3A%2F%2Fpanel.meesharr.space%2Fvpn-control%2Fbundle%2F$token"))
 }
 @Test fun acceptsStrictHttpsSubscriptionsFromQrAndOtherHosts(){
  for(url in listOf("https://ru-kvn.meesharr.space/access/unified/$token","https://subscription.example.test:8443/sub?token=private&format=json")){
   assertEquals(url,SelectionImportLink.parse(url))
   assertEquals(url,SelectionImportLink.parse("unifiedvpn://import?url="+url.encodeURLParameter()))
  }
 }
 @Test fun rejectsUnsafeSchemesCredentialsFragmentsAndAmbiguousArguments(){
  for(url in listOf("http://example.test/sub","file:///secret","javascript:alert(1)","https://user:pass@example.test/sub","https://user@example.test/sub","https://example.test/sub#token","https://example.test/a\nb","https://example.test:0/sub")){
   assertNull(SelectionImportLink.parse(url))
   assertNull(SelectionImportLink.parse("unifiedvpn://import?url="+url.encodeURLParameter()))
  }
  assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$source&url=$source"))
  assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$source&extra=1"))
  assertNull(SelectionImportLink.parse("unifiedvpn://other?url=$source"))
  assertNull(SelectionImportLink.parse("unifiedvpn://import:0?url=$source"))
  assertNull(SelectionImportLink.parse("unifiedvpn://user@import?url=$source"))
  assertNull(SelectionImportLink.parse("unifiedvpn://import?url=$source#extra"))
 }
}
