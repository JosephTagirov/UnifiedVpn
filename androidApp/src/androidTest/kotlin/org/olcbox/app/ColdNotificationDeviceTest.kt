package org.olcbox.app

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.net.VpnService
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

class ColdNotificationDeviceTest {
    @Test fun notificationControlsRenderWithoutOpeningMainActivityFirst() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        assertTrue(context.packageName.endsWith(".shadeqa"))
        runBlocking { assertEquals(setOf("shade012-a", "shade012-b"), AndroidLocationsRepository.get(context).getBundle().locations.map { it.storageId }.toSet()) }
        fun await(label: String, ready: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 45_000
            while (!ready()) { assertTrue(label, SystemClock.elapsedRealtime() < until); SystemClock.sleep(75) }
        }
        assertNull("QA VPN permission is preapproved", VpnService.prepare(context))
        val service = Intent().setClassName(context, OlcboxVpnActions.SERVICE_CLASS_NAME)
        context.startForegroundService(service.setAction(OlcboxVpnActions.ACTION_START_VPN))
        try {
            await("Cold service connected") { OlcboxVpnState.status.value is VpnStatus.Connected }
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 100 }.notification
            val device = UiDevice.getInstance(inst)
            assertTrue(device.openNotification())
            val title = device.wait(Until.findObject(By.res("android", "title").text("Unified VPN")),10_000)
                ?: error("VPN notification title not visible")
            title.click()
            await("Cold chooser must launch without AppActivity") {
                var shown = false
                inst.runOnMainSync { shown = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it.javaClass.name == "org.olcbox.app.ui.activities.VpnProfileChooserActivity" } }
                shown
            }
            inst.waitForIdleSync(); SystemClock.sleep(750)
            assertTrue("Chooser must not kill the VPN process", OlcboxVpnState.status.value is VpnStatus.Connected)
            assertTrue(notification.actions.any { it.title.toString() == "Reconnect" })
        } finally {
            context.startService(service.setAction(OlcboxVpnActions.ACTION_STOP_VPN))
            await("Cold QA VPN stopped") { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        }
    }
}
