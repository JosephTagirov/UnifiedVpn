package org.olcbox.app.data.logging
import kotlin.test.*
class SupportLogsTest {
 @Test fun nativeProxyCredentialsAreMasked(){
  val clean=SupportLogs.redact("vmess://encoded-private\nhysteria2://private-hy-auth@example.test:443\nhy2://private-alias-auth@example.test:443\n{\"auth\":\"private-json-auth\"}")
  listOf("encoded-private","private-hy-auth","private-alias-auth","private-json-auth").forEach { assertFalse(it in clean) }
 }
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
