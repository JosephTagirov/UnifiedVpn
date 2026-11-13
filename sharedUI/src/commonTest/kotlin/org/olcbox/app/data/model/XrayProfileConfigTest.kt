package org.olcbox.app.data.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class XrayProfileConfigTest {
 private val vmess="""{"v":"2","ps":"VMess test","add":"server.example.test","port":"443","id":"11111111-1111-4111-8111-111111111111","aid":"0","net":"ws","host":"cdn.example.test","path":"/socket","tls":"tls","sni":"tls.example.test"}"""
 @Test fun parsesVmessShareAndHy2AliasesWithIpv6AndEncodedAuth() {
  val uri="vmess://"+Base64.encode(vmess.encodeToByteArray())
  val p=assertNotNull(XrayProfileConfig.parse(VpnProfileConfig(type="vmess",uri=uri)))
  assertEquals("server.example.test",p.host);assertEquals("tls.example.test",p.sni);assertEquals("ws",p.transport)
  assertTrue(VpnProfileConfig(type="vmess",rawConfig=vmess).isCompleteFor())
  val h=assertNotNull(XrayProfileConfig.parse(VpnProfileConfig(type="hy2",uri="hy2://user%3Apass@[2001:db8::1]:54443/?sni=tls.example.test#Test")))
  assertEquals("user:pass",h.credential);assertEquals(54443,h.port);assertEquals("2001:db8::1",h.host)
  assertFalse("user:pass" in h.toString())
  assertEquals("hysteria2",VpnProfileConfig.normalizeType("HY2"))
 }
 @Test fun tlsIsStrictAndDeclaredProtocolCannotBeSpoofed() {
  for(uri in listOf("hy2://@example.test:443","hy2://auth@example.test:0","hy2://auth@example.test:99999",
      "hy2://auth@example.test:443/?insecure=1","hy2://auth@example.test:443/?insecure=true",
      "hy2://auth@example.test:443/?sni=a&sni=b","hy2://auth@example.test:443/?obfs=salamander",
      "hy2://auth@example.test:443/?mport=443-450", "hy2://auth@example.test:443/?unsupported=1",
      "hy2://auth%zz@example.test:443", "hy2://auth@example.test:443/unsupported")) {
   assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="hysteria2",uri=uri)))
  }
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="vmess",uri="vless://anything@host:443")))
  assertFalse(VpnProfileConfig(type="hysteria2",uri="vless://anything@host:443",localSocksPort=1080).isCompleteFor())
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="vmess",uri="vmess://garbage")))
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="vmess",rawConfig=vmess.replace("\"aid\":\"0\"","\"aid\":\"64\""))))
 }
 @Test fun hysteriaJsonChecksTypesAndTls() {
  val json="""{"server":"server.example.test:54443","auth":"private-auth","tls":{"sni":"tls.example.test","insecure":false}}"""
  val p=assertNotNull(XrayProfileConfig.parse(VpnProfileConfig(type="hysteria2",rawConfig=json)))
  assertEquals(54443,p.port);assertEquals("tls.example.test",p.sni)
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="hysteria2",rawConfig=json.replace("false","true"))))
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="hysteria2",rawConfig=json.replace("\"private-auth\"","123"))))
  assertNull(XrayProfileConfig.parse(VpnProfileConfig(type="hysteria2",rawConfig=json.replace("\"tls.example.test\"","true"))))
 }
}
