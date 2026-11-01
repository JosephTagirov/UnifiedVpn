package org.olcbox.app

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Allows preparing an isolated, non-debuggable R8 build without root or run-as. */
class PrepareQaFixtureDeviceTest {
    @Test fun prepareOnlyDisposableQaData() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertTrue(context.packageName.endsWith(".shadeqa"))
        val encoded = InstrumentationRegistry.getArguments().getString("qaBundle") ?: error("QA bundle required")
        val raw = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        val bundle = JSONObject(raw); val locations = bundle.getJSONArray("locations")
        assertEquals(2, locations.length())
        val ids = (0 until locations.length()).map { locations.getJSONObject(it).getString("storage_id") }.toSet()
        assertEquals(setOf("shade012-a", "shade012-b"), ids)
        File(context.filesDir, "locations_v4.json").writeText(raw)
    }
}
