package org.olcbox.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

/** Body drags exercise nested scrolling; a drag starting on the handle does not. */
class SplitTunnelBodyDragDeviceTest {
    @Test fun settingsBodyDragFollowsFinger() = checkBodyDrag(appList = false)
    @Test fun appListBodyDragFollowsFinger() = checkBodyDrag(appList = true)

    @Test fun shortSettingsBodyDragReturnsWithoutClosing() = checkBodyDrag(appList = false, shortDrag = true)
    @Test fun shortAppListBodyDragReturnsWithoutClosing() = checkBodyDrag(appList = true, shortDrag = true)

    private fun checkBodyDrag(appList: Boolean, shortDrag: Boolean = false) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertTrue(context.packageName.endsWith(".shadeqa"))
        val device = UiDevice.getInstance(inst)
        inst.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(context.packageName, "org.olcbox.app.AppActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.desc("Split tunneling")), 10_000))
        device.findObject(By.desc("Split tunneling")).click()
        val heading = device.wait(Until.findObject(By.text("Split Tunneling")), 10_000)
            ?: error("Missing split tunneling sheet")
        device.waitForIdle()
        fun clickInSettings(text: String) {
            repeat(6) {
                val item = device.findObject(By.textContains(text))
                if (item != null && item.visibleBounds.height() > 0) { item.click(); return }
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 18)
            }
            error("Missing settings action $text")
        }
        val body = if (appList) {
            clickInSettings("Bypass Selected")
            clickInSettings("Bypassed Apps")
            device.wait(Until.findObject(By.text("Search apps")), 5_000)
            device.waitForIdle()
            device.findObjects(By.scrollable(true)).maxBy { it.visibleBounds.height() }.visibleBounds
        } else heading.visibleBounds
        val x = body.centerX().toFloat()
        val fromY = if (appList) body.top + 45f else body.centerY().toFloat()
        val distance = if (shortDrag) context.resources.displayMetrics.density * 50f else device.displayHeight * 0.48f
        val toY = minOf(fromY + distance, device.displayHeight - 100f)
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun handleTop(): Int? {
            fun find(node: AccessibilityNodeInfo?): Int? {
                if (node == null) return null
                if (node.contentDescription?.toString() == "Drag handle") {
                    val rect = Rect(); node.getBoundsInScreen(rect); return rect.top
                }
                for (i in 0 until node.childCount) { find(node.getChild(i))?.let { return it } }
                return null
            }
            return find(inst.uiAutomation.rootInActiveWindow)
        }
        val samples = mutableListOf<Int>()
        handleTop()?.let(samples::add)
        touch(MotionEvent.ACTION_DOWN, fromY)
        try {
            repeat(40) { step ->
                touch(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * (step + 1) / 40f)
                SystemClock.sleep(20)
                handleTop()?.let(samples::add)
            }
        } finally { touch(MotionEvent.ACTION_UP, toY) }
        inst.sendStatus(2, Bundle().apply { putString(if (appList) "appListBodyDragTops" else "settingsBodyDragTops", samples.joinToString(",")) })
        assertTrue("Tracked sheet throughout body drag: $samples", samples.size >= 30)
        val tolerance = (context.resources.displayMetrics.density * 3).toInt()
        val regressions = samples.zipWithNext().filter { (a, b) -> b < a - tolerance }
        assertTrue("Sheet must follow finger without upward jumps: $samples", regressions.isEmpty())
        assertTrue("Body drag actually moves the sheet: $samples", samples.last() - samples.first() > (toY - fromY) * 0.65f)
        if (shortDrag) {
            device.waitForIdle()
            val top = device.wait(Until.findObject(By.desc("Drag handle")), 5_000)?.visibleBounds?.top
            assertNotNull("Short body drag must keep the sheet open", top)
            assertTrue("Short body drag returns to expanded position: $top vs ${samples.first()}",
                kotlin.math.abs(top!! - samples.first()) <= tolerance)
            device.findObject(By.desc("Drag handle")).click()
        } else if (device.hasObject(By.desc("Drag handle"))) {
            val r = device.findObject(By.desc("Drag handle")).visibleBounds
            device.swipe(x.toInt(), r.bottom + 70, x.toInt(), device.displayHeight - 50, 30)
        }
        assertTrue(device.wait(Until.gone(By.desc("Drag handle")), 5_000))
        assertNotNull(device.wait(Until.findObject(By.desc("Add configuration")), 5_000))
    }
}
