package org.olcbox.app

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

class SplitTunnelSheetDeviceTest {
    @Test fun draggingSplitTunnelAndAppListSheetsKeepsHomeResponsive() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        assertTrue(context.packageName.endsWith(".shadeqa"))
        val device = UiDevice.getInstance(inst)
        inst.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(context.packageName,"org.olcbox.app.AppActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun waitText(text: String) = device.wait(Until.findObject(By.textContains(text)), 10_000) ?: error("Missing $text")
        assertNotNull(device.wait(Until.findObject(By.desc("Add configuration")),10_000))
        assertFalse(device.hasObject(By.text("Add olcRTC location")))
        repeat(3) { attempt ->
            device.findObject(By.desc("Split tunneling")).click()
            waitText("Split Tunneling")
            if (attempt > 0) {
                waitText("Bypass Selected").click(); waitText("Bypassed Apps").click()
                var visible = device.hasObject(By.textContains("com.android.chrome"))
                repeat(4) { if (!visible) {
                    device.swipe(device.displayWidth/2, device.displayHeight*4/5,
                        device.displayWidth/2, device.displayHeight/2, 20)
                    visible = device.hasObject(By.textContains("com.android.chrome"))
                } }
                assertTrue("Loaded application icons", visible)
            }
            val handle = device.wait(Until.findObject(By.desc("Drag handle")),5_000) ?: error("Missing sheet handle")
            val bounds = handle.visibleBounds
            device.swipe(bounds.centerX(),bounds.centerY(),bounds.centerX(),device.displayHeight-80,35)
            assertTrue("Sheet closes after drag", device.wait(Until.gone(By.desc("Drag handle")),5_000))
            assertNotNull(device.wait(Until.findObject(By.desc("Add configuration")),5_000))
            assertFalse(device.hasObject(By.text("Add olcRTC location")))
        }
    }
}
