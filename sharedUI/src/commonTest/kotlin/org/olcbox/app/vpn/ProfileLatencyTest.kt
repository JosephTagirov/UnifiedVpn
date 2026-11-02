package org.olcbox.app.vpn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.test.*
class ProfileLatencyTest {
 private val profile=VpnProfileConfig(type="vless",uri="vless://test@example.invalid:443#First")
 private val proxy=SubscriptionFetchProxy("127.0.0.1",12345)
 @Test fun renamedProfilesMatchButChangedCredentialsDoNot() {
  val session=ProfileLatencySession(profile,LocationConfig(),proxy)
  assertTrue(session.matches(profile.copy(name="Renamed",uri=profile.uri!!.substringBefore('#')+"#New"),LocationConfig()))
  assertFalse(session.matches(profile.copy(uri=profile.uri!!.replace("test@","wrong@")),LocationConfig()))
 }
 @Test fun differentVkRoomsOrKeysCannotUseCurrentTunnel() {
  val vk=VpnProfileConfig.olcRtc();val config=LocationConfig(id="room",key="a".repeat(64),bypassProvider="vkcalls")
  val session=ProfileLatencySession(vk,config,proxy)
  assertTrue(session.matches(vk,config.copy(name="New")))
  assertFalse(session.matches(vk,config.copy(key="b".repeat(64))))
 }
 @Test fun anOpenSocksPortWithoutHttpsIsNotSuccess()=runTest { assertNull(ProfileLatency.measure(proxy){false}) }
 @Test fun successfulProbeUsesTheExactProxy()=runTest {
  assertNotNull(ProfileLatency.measure(proxy){assertSame(proxy,it);true})
 }
 @Test fun changingSessionDuringRequestDiscardsMeasurement()=runTest {
  val first=ProfileLatencySession(profile,LocationConfig(),proxy);var current:ProfileLatencySession?=first
  assertFailsWith<VpnPingUnavailableException>{ProfileLatency.connected(first,{current}){current=null;true}}
 }
 @Test fun cancellationPropagatesInsteadOfShowingOffline()=runTest {
  assertFailsWith<CancellationException>{ProfileLatency.measure(proxy){throw CancellationException("test")}}
 }
}
