package org.olcbox.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/** End-to-end checks require disposable shadeqa app data; never run against a user's client. */
class NotificationControlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun eventually(label: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!test()) { assertTrue("Timed out: $label", SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(75) }
    }
    private fun service(action: String, id: String? = null) {
        context.startService(Intent().setClassName(context, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(action).putExtra(OlcboxVpnActions.EXTRA_PROFILE_STORAGE_ID, id))
    }
    private fun notification(): Notification = notifications.activeNotifications.single { it.id == 100 }.notification
    private fun resumedChooser(): Activity? {
        var result: Activity? = null
        instrumentation.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).firstOrNull { it.javaClass.simpleName == "VpnProfileChooserActivity" } }
        return result
    }
    private fun probe() = runBlocking {
        val entry = AndroidLocationsRepository.get(context).getBundle().let { b -> b.locations.single { it.storageId == b.activeLocationId } }
        val manager = AndroidVpnManager(context)
        try { assertNotNull("Authenticated HTTPS through the active VLESS proxy", manager.ping(entry.location, entry.profile)) }
        finally { manager.closeUi() }
    }
    @Test fun notificationChooserReconnectAndSwitchKeepWorking() {
        assertTrue("Only the disposable QA package may run this test", context.packageName.endsWith(".shadeqa"))
        val ids = runBlocking { AndroidLocationsRepository.get(context).getBundle().locations.map { it.storageId }.toSet() }
        assertEquals(setOf("shade011-a", "shade011-b"), ids)
        runBlocking { AndroidLocationsRepository.get(context).setActiveLocationId("shade011-a") }
        instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(context.packageName, "org.olcbox.app.AppActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        service(OlcboxVpnActions.ACTION_START_VPN)
        try {
            eventually("initial VLESS connection") { OlcboxVpnState.status.value is VpnStatus.Connected }
            probe()
            val posted = notification()
            assertEquals(listOf("Profiles", "Stop", "Reconnect"), posted.actions.map { it.title.toString() })
            val originalChooser = posted.contentIntent
            originalChooser.send()
            eventually("notification profile chooser rendered") { resumedChooser() != null }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            assertNotNull("Adaptive launcher icon must not crash the chooser", resumedChooser())
            val before = OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") }
            posted.actions.single { it.title.toString() == "Reconnect" }.actionIntent.send()
            eventually("healthy active profile reconnected") {
                OlcboxVpnState.status.value is VpnStatus.Connected &&
                    OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") } > before
            }
            probe()
            assertEquals("Posted chooser PendingIntent survives status updates", originalChooser, notification().contentIntent)
            resumedChooser()?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            instrumentation.waitForIdleSync()
            originalChooser.send()
            eventually("old notification tap is still valid") { resumedChooser() != null }
            val beforeSwitch = OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") }
            service(OlcboxVpnActions.ACTION_APPLY_SELECTED_PROFILE, "shade011-b")
            eventually("second profile connected") {
                OlcboxVpnState.status.value is VpnStatus.Connected &&
                    OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") } > beforeSwitch
            }
            val selected = runBlocking { AndroidLocationsRepository.get(context).getBundle().activeLocationId }
            assertEquals("shade011-b", selected)
            probe()
            originalChooser.send()
            eventually("chooser stays usable after switching profiles") { resumedChooser() != null }
        } finally {
            service(OlcboxVpnActions.ACTION_STOP_VPN)
            eventually("QA VPN stopped") { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        }
    }
}
