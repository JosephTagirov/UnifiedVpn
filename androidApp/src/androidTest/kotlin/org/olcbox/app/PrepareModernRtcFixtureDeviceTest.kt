package org.olcbox.app

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrepareModernRtcFixtureDeviceTest {
 @Test fun prepareOrClearOnlyTemporaryRtcProfiles() {
  val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
  assertTrue(context.packageName.endsWith(".shadeqa"))
  val file=File(context.filesDir,"locations_v4.json")
  val clear=InstrumentationRegistry.getArguments().getString("clearQa")=="true"
  val raw=if(clear)file.readText() else String(Base64.decode(InstrumentationRegistry.getArguments().getString("qaBundle")?:error("Temporary fixture required"),Base64.DEFAULT),Charsets.UTF_8)
  val locations=JSONObject(raw).getJSONArray("locations")
  assertEquals(2,locations.length())
  assertEquals(setOf("shade014-jazz","shade014-vk"),(0 until locations.length()).map{locations.getJSONObject(it).getString("storage_id")}.toSet())
  if(clear)file.writeText("""{"version":5,"locations":[]}""") else file.writeText(raw)
 }
}
