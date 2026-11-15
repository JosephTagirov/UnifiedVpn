package org.olcbox.app.vpn.desktop

import java.nio.file.Path
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.ManagedEntryDns
import kotlin.test.*

class WindowsPhysicalDohQueryTest {
    private val network = PhysicalUpstream("Ethernet", 6, "192.168.0.10")
    private val query = ManagedEntryDns.query(0x5263)
    private fun answer(): ByteArray = query.copyOf().also {
        it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1
    } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 178.toByte(), 208.toByte(), 70, 181.toByte())
    private val metadata = "192.168.0.10\n8.8.8.8\n200\n0\n"

    @Test fun onlyTheOwnedExactDnsQuestionCanBeSent() {
        assertTrue(WindowsPhysicalDohQuery.validRequest(network, query))
        assertFalse(WindowsPhysicalDohQuery.validRequest(network, query.copyOf().also { it[13] = 'x'.code.toByte() }))
        assertFalse(WindowsPhysicalDohQuery.validRequest(network, query + byteArrayOf(0)))
        assertFalse(WindowsPhysicalDohQuery.validRequest(network.copy(ipv4Address = "127.0.0.1"), query))
    }

    @Test fun curlCannotReadUserConfigurationOrProxySettingsAndRetainsTlsAndPeerPinning() {
        val command = WindowsPhysicalDohQuery.command(Path.of("C:/Windows/System32/curl.exe"), network, query, Path.of("C:/private/answer.bin"))
        assertEquals("-q", command[1])
        fun value(option: String) = command[command.indexOf(option) + 1]
        assertEquals("*", value("--noproxy")); assertEquals(network.ipv4Address, value("--interface"))
        assertEquals("dns.google:443:8.8.8.8", value("--resolve"))
        assertEquals("3", value("--max-time")); assertEquals("4096", value("--max-filesize")); assertEquals("0", value("--max-redirs"))
        assertEquals("=https", value("--proto"))
        assertTrue(command.none { it in setOf("-k", "--insecure", "-L", "--location") })
        assertTrue(command.last().startsWith("https://dns.google/dns-query?dns="))
    }

    @Test fun rejectsWrongPhysicalSourcePeerStatusCertificateOrDnsContent() {
        val body = answer()
        assertContentEquals(body, WindowsPhysicalDohQuery.verifiedAnswer(network, query, body, metadata))
        for (wrong in listOf(metadata.replace("192.168.0.10", "172.19.0.1"), metadata.replace("8.8.8.8", "1.1.1.1"),
            metadata.replace("200", "302"), metadata.replace("\n0\n", "\n1\n"), metadata + "extra\n"))
            assertNull(WindowsPhysicalDohQuery.verifiedAnswer(network, query, body, wrong))
        assertNull(WindowsPhysicalDohQuery.verifiedAnswer(network, query, body.copyOf().also { it[1] = 0 }, metadata))
        assertNull(WindowsPhysicalDohQuery.verifiedAnswer(network, query, body.copyOf(body.size - 1), metadata))
        assertNull(WindowsPhysicalDohQuery.verifiedAnswer(network, query, ByteArray(4097), metadata))
    }

    @Test fun nativeOwnedNameUsesVerifiedSourceBoundTlsAndDoesNotSilentlyAcceptTheFallback() {
        if (DesktopPaths.os != DesktopOs.Windows || System.getenv("UNIFIEDVPN_WINDOWS_PHYSICAL_DNS_TEST") != "1") return
        val physical = DesktopPhysicalInterface.currentWindowsNetwork()
        val response = assertNotNull(WindowsPhysicalDohQuery.exchange(physical, query), "Owned DNS-over-HTTPS did not return a validated answer")
        assertContains(ManagedEntryDns.addresses(query, response), "178.208.70.181")
    }
}
