package org.olcbox.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/** Uses SystemUI and accessibility, so release Compose code needs no test hooks. */
class QuickSettingsControlsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private fun shell(command: String): String = inst.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!ready()) { assertTrue(label, SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(75) }
    }
    private fun button(text: String) = device.wait(Until.findObject(By.text(text)), 10_000)
        ?: error("Missing quick control: $text")

    @Test fun systemTileOpensStartStopReconnectAndProfilesWithoutStoppingVpn() {
        assertTrue(context.packageName.endsWith(".shadeqa"))
        val repo = AndroidLocationsRepository.get(context)
        assertEquals(setOf("shade012-a", "shade012-b"), runBlocking { repo.getBundle().locations.map { it.storageId }.toSet() })
        val component = context.packageName + "/org.olcbox.app.QuickSettingsTileService"
        fun openTile() {
            shell("cmd statusbar expand-settings"); SystemClock.sleep(700)
            shell("cmd statusbar click-tile $component")
        }
        shell("cmd statusbar add-tile $component")
        try {
            openTile()
            button("Connect")
            (device.wait(Until.findObject(By.textContains("QA B VLESS")),10_000) ?: error("Missing QA B profile")).click()
            await("Disconnected profile selection") { runBlocking { repo.getBundle().activeLocationId } == "shade012-b" }
            button("Connect").click()
            await("Start from actual tile") { OlcboxVpnState.status.value is VpnStatus.Connected }
            button("Reconnect")
            device.wait(Until.findObject(By.desc("Close")), 5_000)!!.click()
            openTile()
            button("Reconnect")
            assertTrue("Tile tap preserves the active tunnel", OlcboxVpnState.status.value is VpnStatus.Connected)
            val before = OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") }
            button("Reconnect").click()
            await("Reconnect from actual tile") {
                OlcboxVpnState.status.value is VpnStatus.Connected &&
                    OlcboxVpnState.logs.value.count { it.contains("Notification profile switch connected") } > before
            }
            runBlocking {
                val manager = AndroidVpnManager(context)
                val entry = repo.getBundle().locations.single { it.storageId == "shade012-b" }
                try { assertNotNull("HTTPS after tile reconnect", manager.ping(entry.location, entry.profile)) }
                finally { manager.closeUi() }
            }
            button("Stop").click()
            await("Explicit stop") { OlcboxVpnState.status.value is VpnStatus.Disconnected }
            button("Connect")
        } finally {
            if (OlcboxVpnState.status.value !is VpnStatus.Disconnected) context.startService(
                Intent().setClassName(context, OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(OlcboxVpnActions.ACTION_STOP_VPN))
            shell("cmd statusbar remove-tile $component"); shell("cmd statusbar collapse")
        }
    }
}
