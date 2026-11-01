package org.olcbox.app.update
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class UpdateMirrorTest {
 private fun feed(url:String,digest:String="a".repeat(64))="""{"tag_name":"v0.0.23","html_url":"https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.0.23","assets":[{"name":"UnifiedVPN-android-build.2026110104.apk","browser_download_url":"$url","digest":"sha256:$digest","size":123}]}"""
 @Test fun serverPrimaryAndSameHashGithubIsFallback()=runTest {
  val service=AppUpdateService(httpClient=HttpClient(MockEngine { request -> respond(feed(if(request.url.host=="panel.meesharr.space")"https://panel.meesharr.space/file.apk" else "https://github.com/file.apk")) }),platform=UpdatePlatform("android","arm64"))
  val result=service.check(ReleaseChannel.Stable).getOrThrow()
  assertEquals("https://panel.meesharr.space/file.apk",result.asset.downloadUrl)
  assertEquals(listOf("https://github.com/file.apk"),result.asset.fallbackUrls)
 }
 @Test fun serverUnavailableUsesGithub()=runTest {
  val service=AppUpdateService(httpClient=HttpClient(MockEngine { request -> if(request.url.host=="panel.meesharr.space") respond("",HttpStatusCode.ServiceUnavailable) else respond(feed("https://github.com/file.apk")) }),platform=UpdatePlatform("android","arm64"))
  assertEquals("https://github.com/file.apk",service.check(ReleaseChannel.Stable).getOrThrow().asset.downloadUrl)
 }
 @Test fun differentHashNeverBecomesDownloadFallback()=runTest {
  val service=AppUpdateService(httpClient=HttpClient(MockEngine { request -> respond(feed("https://"+request.url.host+"/file.apk",if(request.url.host=="panel.meesharr.space")"a".repeat(64) else "b".repeat(64))) }),platform=UpdatePlatform("android","arm64"))
  assertTrue(service.check(ReleaseChannel.Stable).getOrThrow().asset.fallbackUrls.isEmpty())
 }
 @Test fun downloadFallsBackAndCancellationPropagates()=runTest {
  val seen=mutableListOf<String>();val asset=AppUpdateAsset("x","https://server",1,fallbackUrls=listOf("https://github"))
  assertEquals("ok",withUpdateMirrors(asset){seen+=it.downloadUrl;if(seen.size==1)error("offline") else "ok"})
  assertEquals(2,seen.size)
  assertFailsWith<kotlinx.coroutines.CancellationException>{withUpdateMirrors(asset){throw kotlinx.coroutines.CancellationException()}}
 }
}
