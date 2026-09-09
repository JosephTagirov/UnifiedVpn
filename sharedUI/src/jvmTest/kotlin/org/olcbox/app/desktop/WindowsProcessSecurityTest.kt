package org.olcbox.app.desktop

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsProcessSecurityTest {
    @Test
    fun nonWindowsProcessIsNeverRefusedWithoutQueryingWindowsApis() {
        listOf(DesktopOs.Linux, DesktopOs.Other).forEach { os ->
            assertFalse(
                WindowsProcessSecurity.shouldRefuseProcess(os) {
                    error("Windows token API must not be queried")
                }
            )
        }
    }

    @Test
    fun verifiedNormalWindowsProcessIsAllowed() {
        assertFalse(
            WindowsProcessSecurity.shouldRefuseProcess(DesktopOs.Windows) { false }
        )
    }

    @Test
    fun elevatedOrUnverifiableWindowsProcessIsRefused() {
        assertTrue(
            WindowsProcessSecurity.shouldRefuseProcess(DesktopOs.Windows) { true }
        )
        assertTrue(
            WindowsProcessSecurity.shouldRefuseProcess(DesktopOs.Windows) {
                error("simulated token API failure")
            }
        )
    }

    @Test
    fun trustedExecutableMustRemainInsideTheSystemDirectory() {
        val root = createTempDirectory("windows-system-directory-test")
        try {
            val powershell = root.resolve("WindowsPowerShell/v1.0/powershell.exe")
            Files.createDirectories(powershell.parent)
            Files.writeString(powershell, "test")

            assertEquals(
                powershell,
                WindowsTrustedExecutables.resolveTrustedExecutable(
                    root,
                    listOf("WindowsPowerShell", "v1.0", "powershell.exe")
                )
            )
            assertFailsWith<IllegalArgumentException> {
                WindowsTrustedExecutables.resolveTrustedExecutable(
                    root,
                    listOf("..", "outside.exe")
                ) { true }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
