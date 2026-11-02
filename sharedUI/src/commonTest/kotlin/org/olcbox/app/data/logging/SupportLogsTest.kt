package org.olcbox.app.data.logging
import kotlin.test.*
class SupportLogsTest {
 @Test fun configUrisKeysAndPrivateLinksAreMasked(){
  val input="PrivateKey = ABCDEFG\nvless://user@server?key=secret\nhttps://panel.meesharr.space/vpn-control/bundle/"+"a".repeat(43)+"\n"+"b".repeat(64)+"\nAuthorization: Bearer secret-token"
  val clean=SupportLogs.redact(input)
  listOf("ABCDEFG","vless://","a".repeat(43),"b".repeat(64),"secret-token").forEach{assertFalse(clean.contains(it))}
 }
 @Test fun targetMustBePrivateOwnSubscription(){
  SupportLogs.selectTarget("https://evil.test/unified/"+"a".repeat(43));assertNull(SupportLogs.target.value)
  SupportLogs.selectTarget("https://panel.meesharr.space/vpn-control/admin/");assertNull(SupportLogs.target.value)
  SupportLogs.selectTarget("https://panel.meesharr.space/vpn-control/bundle/"+"a".repeat(43));assertEquals("a".repeat(43),SupportLogs.target.value)
  SupportLogs.selectTarget(null)
 }
}
