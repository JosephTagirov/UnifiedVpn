package org.olcbox.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/** Actual guest-Android VPN; all profiles are dedicated, short-lived QA identities. */
class ModernRtcControlsDeviceTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private val context=inst.targetContext
 private fun eventually(label:String,condition:()->Boolean) {
  val end=SystemClock.elapsedRealtime()+120_000
  while(!condition()){assertTrue(label,SystemClock.elapsedRealtime()<end);SystemClock.sleep(100)}
 }
 private fun action(name:String,id:String?=null) {
  context.startService(Intent().setClassName(context,OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(name).putExtra(OlcboxVpnActions.EXTRA_PROFILE_STORAGE_ID,id))
 }
 private fun probe(label:String)=runBlocking {
  val repo=AndroidLocationsRepository.get(context);val bundle=repo.getBundle();val entry=bundle.locations.single{it.storageId==bundle.activeLocationId}
  val manager=AndroidVpnManager(context)
  try{assertNotNull("Authenticated HTTPS via "+label,manager.ping(entry.location,entry.profile))}finally{manager.closeUi()}
 }
 @Test fun jazzTunHttpsReconnectNotificationAndVkRegression() {
  assertTrue(context.packageName.endsWith(".shadeqa"))
  val repo=AndroidLocationsRepository.get(context)
  runBlocking {assertEquals(setOf("shade014-jazz","shade014-vk"),repo.getBundle().locations.map{it.storageId}.toSet());repo.setActiveLocationId("shade014-jazz")}
  assertNull("QA VPN permission must be preapproved",VpnService.prepare(context))
  inst.startActivitySync(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName,"org.olcbox.app.AppActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  action(OlcboxVpnActions.ACTION_START_VPN)
  try {
   eventually("Jazz VPN connected"){OlcboxVpnState.status.value is VpnStatus.Connected};probe("SaluteJazz")
   assertTrue("RTC signaling DNS selected automatically",OlcboxVpnState.logs.value.any{it.contains("using RTC signaling DNS")})
   val posted=context.getSystemService(NotificationManager::class.java).activeNotifications.single{it.id==100}.notification
   assertTrue("Jazz has Reconnect",posted.actions.any{it.title.toString()=="Reconnect"})
   val before=OlcboxVpnState.logs.value.count{it.contains("Notification profile switch connected")}
   posted.actions.single{it.title.toString()=="Reconnect"}.actionIntent.send()
   eventually("Jazz reconnect completed"){OlcboxVpnState.status.value is VpnStatus.Connected&&OlcboxVpnState.logs.value.count{it.contains("Notification profile switch connected")}>before}
   probe("Jazz after reconnect")
   val beforeSwitch=OlcboxVpnState.logs.value.count{it.contains("Notification profile switch connected")}
   action(OlcboxVpnActions.ACTION_APPLY_SELECTED_PROFILE,"shade014-vk")
   eventually("VK regression switch"){OlcboxVpnState.status.value is VpnStatus.Connected&&OlcboxVpnState.logs.value.count{it.contains("Notification profile switch connected")}>beforeSwitch}
   assertEquals("shade014-vk",runBlocking {repo.getBundle().activeLocationId});probe("VK Calls")
  } finally {
   action(OlcboxVpnActions.ACTION_STOP_VPN)
   eventually("Temporary QA VPN stopped"){OlcboxVpnState.status.value is VpnStatus.Disconnected}
  }
 }
}
