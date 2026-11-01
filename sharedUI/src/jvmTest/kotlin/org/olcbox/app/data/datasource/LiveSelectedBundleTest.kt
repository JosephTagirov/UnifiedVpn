package org.olcbox.app.data.datasource
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.*
import org.olcbox.app.data.repository.LocationImportResult
import kotlin.test.*
class LiveSelectedBundleTest {
 private class Memory(var bundle:LocationBundleV4):LocationsDataSource {
  override suspend fun loadLocationBundle()=bundle
  override suspend fun saveLocationBundle(bundle:LocationBundleV4){this.bundle=bundle}
  override suspend fun loadLegacyLocations()=emptyList<Pair<String,String>>()
  override suspend fun loadLegacyActiveLocationId():String?=null
 }
 @Test fun realSelectedSetsImportExactlyAndDoNotReplaceExistingProfile()=runBlocking{
  val fixture=System.getenv("UVPN_SELECTED_QA_FILE")?.takeIf{it.isNotBlank()}?:return@runBlocking
  val root=Json.parseToJsonElement(Files.readString(Path.of(fixture))).jsonObject
  val devices=root.getValue("devices").jsonArray
  val old=LocationEntry.fromProfile("old",VpnProfileConfig(type="vless",name="Existing",uri="vless://old@example.test:443"))
  val memory=Memory(LocationBundleV4(activeLocationId="old",locations=listOf(old)))
  val repo=LocationsRepositoryImpl(memory)
  val expected=mutableMapOf("old" to 1)
  devices.forEachIndexed { i,device ->
   val source=device.jsonObject.getValue("subscriptionUrl").jsonPrimitive.content
   val count=device.jsonObject.getValue("protocols").jsonArray.size
   assertTrue(repo.importTextDetailed(source) is LocationImportResult.Success,"Live selected set did not import")
   assertEquals(count,repo.getAllLocations().count{it.subscriptionUrl==source})
   assertEquals("old",repo.getActiveLocationId())
   assertTrue(repo.importTextDetailed(source) is LocationImportResult.Success)
   assertEquals(count,repo.getAllLocations().count{it.subscriptionUrl==source})
  }
  val imported=repo.getAllLocations()
  assertEquals(8,imported.size)
  assertEquals(setOf("amnezia_wg","vless","openflux","olcrtc"),imported.filter{it.storageId!="old"}.map{it.profile.normalizedType}.toSet())
  assertTrue(imported.any{it.profile.normalizedType==VpnProfileConfig.TYPE_AMNEZIA_WG&&it.profile.rawConfig.orEmpty().contains("[Peer]")})
  assertTrue(imported.any{it.profile.isOlcRtc()&&it.location.isVkCalls()})
  assertEquals("old",repo.getActiveLocationId())
 }
}
