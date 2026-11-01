package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WindowsProxyRecoveryTest {
    private class Registry {
        val values = mutableMapOf("ProxyEnable" to "0x1", "ProxyServer" to "work-proxy:8080")
        var failRestore = false
        suspend fun execute(args: List<String>): String {
            if (args.first() == "powershell") return ""
            val name = args[args.indexOf("/v") + 1]
            when (args[1]) {
                "query" -> return "    $name    REG_SZ    ${values[name] ?: error("Missing value")}"
                "add" -> {
                    if (failRestore && name == "ProxyEnable") error("Registry write failed")
                    values[name] = args[args.indexOf("/d") + 1]
                }
                "delete" -> values.remove(name)
            }
            return ""
        }
    }

    private val pac = "http://127.0.0.1:10809/proxy.pac"
    private fun controller(reg: Registry, journal: Path) =
        WindowsProxyController("reg", "powershell", journal, reg::execute)

    @Test fun crashRecoveryRestoresOnlyTheValuesOwnedByUnifiedVpn() = runBlocking {
        val dir = Files.createTempDirectory("proxy-recovery-")
        val journal = dir.resolve("lease.json")
        val reg = Registry()
        reg.values["AutoConfigURL"] = pac
        reg.values["ProxyEnable"] = "0"
        reg.values["ProxyServer"] = "new-work-proxy:9090"
        Files.writeString(journal, Json.encodeToString(WindowsProxyLease(pac,
            WindowsProxyState("0x1", "old-work-proxy:8080", null, null))))
        try {
            controller(reg, journal).recover()
            assertEquals("1", reg.values["ProxyEnable"])
            assertEquals(null, reg.values["AutoConfigURL"])
            assertEquals("new-work-proxy:9090", reg.values["ProxyServer"])
            assertFalse(Files.exists(journal))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun stopDoesNotOverwriteAProxySelectedByAnotherApplication() = runBlocking {
        val dir = Files.createTempDirectory("proxy-external-")
        val journal = dir.resolve("lease.json")
        val reg = Registry()
        val ctl = controller(reg, journal)
        try {
            ctl.enable(pac)
            assertTrue(Files.exists(journal))
            reg.values["AutoConfigURL"] = "https://work.example/proxy.pac"
            reg.values["ProxyEnable"] = "1"
            ctl.restore()
            assertEquals("https://work.example/proxy.pac", reg.values["AutoConfigURL"])
            assertEquals("1", reg.values["ProxyEnable"])
            assertFalse(Files.exists(journal))
        } finally { ctl.restore(); dir.toFile().deleteRecursively() }
    }

    @Test fun runningOwnerIsNotRecoveredByAnotherInstanceOrStartupJob() = runBlocking {
        val dir = Files.createTempDirectory("proxy-owner-")
        val journal = dir.resolve("lease.json")
        val reg = Registry()
        val owner = controller(reg, journal)
        val other = controller(reg, journal)
        try {
            owner.enable(pac)
            other.recover()
            owner.recover()
            assertEquals(pac, reg.values["AutoConfigURL"])
            assertFailsWith<IllegalStateException> { other.enable(pac) }
            owner.restore()
            assertEquals(null, reg.values["AutoConfigURL"])
            assertEquals("1", reg.values["ProxyEnable"])
        } finally { owner.restore(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedRestoreKeepsTheJournalForTheNextStartup() = runBlocking {
        val dir = Files.createTempDirectory("proxy-retry-")
        val journal = dir.resolve("lease.json")
        val reg = Registry()
        val ctl = controller(reg, journal)
        try {
            ctl.enable(pac)
            reg.failRestore = true
            assertFailsWith<IllegalStateException> { ctl.restore() }
            assertTrue(Files.exists(journal))
            reg.failRestore = false
            controller(reg, journal).recover()
            assertEquals(null, reg.values["AutoConfigURL"])
            assertFalse(Files.exists(journal))
        } finally { reg.failRestore = false; ctl.restore(); dir.toFile().deleteRecursively() }
    }
}
