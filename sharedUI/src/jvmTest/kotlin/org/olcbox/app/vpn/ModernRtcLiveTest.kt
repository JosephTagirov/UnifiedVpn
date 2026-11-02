package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ModernRtcLiveTest {
 @Test fun temporaryRelaysPassHttpsAndWrongJazzKeyCannotPass()=runBlocking {
  val fixture=System.getenv("UNIFIEDVPN_RTC_FIXTURE")?:return@runBlocking
  val root=Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"));require(Path.of(System.getenv("APPDATA")).startsWith(root))
  val data=Files.createDirectories(root.resolve("rtc-profiles"));Files.copy(Path.of(fixture),data.resolve("locations_v4.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  val repo=LocationsRepositoryImpl(JvmLocationsDataSourceImpl(data));val manager=DesktopVpnManager.latencyProbe(repo)
  try {
   val entries=repo.getAllLocations();assertEquals(setOf("shade014-jazz","shade014-vk","shade014-support"),entries.map{it.storageId}.toSet())
   for(entry in entries.filter{it.location.usesModernRtcEngine()}){val ping=manager.ping(entry.location,entry.profile);assertNotNull(ping,"Actual HTTPS through "+entry.location.providerName());assertTrue(ping>0);assertEquals(VpnStatus.Disconnected,manager.status.value);println("Verified "+entry.location.providerName()+" HTTPS: "+ping+" ms")}
   val jazz=entries.single{it.location.bypassProvider=="salutejazz"}
   assertNull(manager.ping(jazz.location.copy(key="f".repeat(64)),jazz.profile),"A listening local SOCKS must not make an invalid key pass")
  }finally{manager.close()}
 }
}
