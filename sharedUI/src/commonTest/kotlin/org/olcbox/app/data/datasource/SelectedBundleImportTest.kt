package org.olcbox.app.data.datasource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*
import org.olcbox.app.data.model.*
import org.olcbox.app.data.repository.LocationImportResult
@OptIn(ExperimentalEncodingApi::class)
class SelectedBundleImportTest {
 private class Memory(var bundle:LocationBundleV4?=null):LocationsDataSource {
  override suspend fun loadLocationBundle()=bundle
  override suspend fun saveLocationBundle(bundle:LocationBundleV4){this.bundle=bundle}
  override suspend fun loadLegacyLocations()=emptyList<Pair<String,String>>()
  override suspend fun loadLegacyActiveLocationId():String?=null
 }
 private fun payload(types:List<String>):String {
  val entries=types.mapIndexed { i,type ->
   if(type=="olcrtc")LocationEntry.from("selection_$i",LocationConfig("VK Calls","https://vk.ru/call/join/testroom","a".repeat(64),LocationConfig.PROVIDER_VK_CALLS))
   else LocationEntry.fromProfile("selection_$i",VpnProfileConfig(type=type,name=type,rawConfig=if(type=="amnezia_wg")"[Interface]\nPrivateKey = private\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = public\nEndpoint = example.test:8443" else "vless://00000000-0000-4000-8000-000000000001@example.test:443?security=tls&type=ws#VLESS"))
  }
  return Base64.encode(buildJsonObject { put("format","unifiedvpn-selection-v1");put("profiles",Json.encodeToJsonElement(entries)) }.toString().encodeToByteArray())
 }
 @Test fun mixedSetIncludesAwgAndPreservesExistingProfileAndSelection()=runTest{
  val old=LocationEntry.fromProfile("old",VpnProfileConfig(type="vless",name="Existing",uri="vless://old@example.test:443"))
  val memory=Memory(LocationBundleV4(activeLocationId="old",locations=listOf(old)))
  val body=payload(listOf("amnezia_wg","vless","olcrtc"))
  val client=HttpClient(MockEngine { respond(body,headers=headersOf(HttpHeaders.ContentType,"text/plain")) })
  val repo=LocationsRepositoryImpl(memory,client,subscriptionHttpClientFactory={_,_->client})
  val url="https://panel.meesharr.space/vpn-control/bundle/"+"a".repeat(43)
  assertTrue(repo.importTextDetailed(url) is LocationImportResult.Success)
  val all=repo.getAllLocations();assertEquals(4,all.size);assertEquals("old",repo.getActiveLocationId())
  assertEquals(setOf("amnezia_wg","vless","olcrtc"),all.filter{it.subscriptionUrl==url}.map{it.profile.normalizedType}.toSet())
  assertTrue(repo.importTextDetailed(url) is LocationImportResult.Success)
  assertEquals(4,repo.getAllLocations().size);assertEquals("old",repo.getActiveLocationId())
 }
 @Test fun subsetImportDoesNotInventOtherProtocols()=runTest{
  val client=HttpClient(MockEngine { respond("unused") });val repo=LocationsRepositoryImpl(Memory(),client)
  assertTrue(repo.importTextDetailed(payload(listOf("vless"))) is LocationImportResult.Success)
  assertEquals(listOf("vless"),repo.getAllLocations().map{it.profile.normalizedType})
 }
 @Test fun malformedSelectedSetIsRejectedAsAWhole()=runTest{
  val client=HttpClient(MockEngine { respond("unused") });val repo=LocationsRepositoryImpl(Memory(),client)
  val bad=Base64.encode("{\"format\":\"unifiedvpn-selection-v1\",\"profiles\":[{}]}".encodeToByteArray())
  assertTrue(repo.importTextDetailed(bad) is LocationImportResult.Failure)
  assertTrue(repo.getAllLocations().isEmpty())
 }
}
