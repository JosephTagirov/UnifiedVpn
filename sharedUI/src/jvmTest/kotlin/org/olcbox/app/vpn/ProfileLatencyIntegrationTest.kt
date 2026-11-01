package org.olcbox.app.vpn
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
class ProfileLatencyIntegrationTest {
 @Test fun allNativeProtocolsCarryHttpsAndInvalidVlessCannotPass()=runBlocking {
  val fixture=System.getenv("UNIFIEDVPN_PING_FIXTURE") ?: return@runBlocking
  val root=Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"))
  require(Path.of(System.getenv("APPDATA")).startsWith(root))
  val data=Files.createDirectories(root.resolve("ping-profiles"))
  Files.copy(Path.of(fixture),data.resolve("locations_v4.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  val repo=LocationsRepositoryImpl(JvmLocationsDataSourceImpl(data));val manager=DesktopVpnManager.latencyProbe(repo)
  try {
   val entries=repo.getAllLocations();assertEquals(4,entries.size)
   for(entry in entries) {
    val result=manager.ping(entry.location,entry.profile)
    assertNotNull(result,"HTTPS latency failed for "+entry.profile.typeLabel())
    assertTrue(result>0);println("Verified HTTPS latency: "+(if(entry.location.isVkCalls()) "VK Calls" else entry.profile.typeLabel())+" "+result+"ms")
    assertEquals(VpnStatus.Disconnected,manager.status.value)
   }
   val vless=entries.first{it.profile.normalizedType=="vless"}
   val wrong=vless.profile.copy(uri=vless.profile.uri!!.replace(Regex("(?<=vless://)[^@]+"),"00000000-0000-0000-0000-000000000000"))
   assertNull(manager.ping(vless.location,wrong),"Open port must not pass with an invalid key")
   println("Verified invalid VLESS key is rejected despite reachable HTTPS listener")
  } finally { manager.close() }
 }
}
