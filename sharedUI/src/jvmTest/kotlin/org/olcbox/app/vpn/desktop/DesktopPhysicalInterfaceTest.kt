package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPhysicalInterfaceTest {
    @Test fun preservesTheWindowsFriendlyAliasExpectedByTheGoCore() {
        assertEquals("Wi-Fi", DesktopPhysicalInterface.parse("\uFEFF\"Wi-Fi\"\r\n"))
        assertEquals("Ethernet 2", DesktopPhysicalInterface.parse("\"Ethernet 2\""))
        assertEquals("Сеть", DesktopPhysicalInterface.parse("\"Сеть\""))
    }

    @Test fun rejectsMissingOrMalformedDiscoveryOutputInsteadOfFallingBackToTheTun() {
        for (output in listOf("", "null", "123", "{}", "[\"Wi-Fi\"]", "\"\"", "\"bad\\u0000name\"", "Windows error")) {
            assertNull(DesktopPhysicalInterface.parse(output))
        }
    }

    @Test fun discoverySelectsOnlyAnActivePhysicalDefaultRouteBeforeCapture() {
        val command = DesktopPhysicalInterface.command("C:/trusted/powershell.exe")
        assertEquals("C:/trusted/powershell.exe", command.first())
        val script = command.last()
        assertContains(script, "Get-NetAdapter -Physical")
        assertContains(script, "Status -eq Up")
        assertContains(script, "0.0.0.0/0")
        assertContains(script, "RouteMetric + [int]")
        assertContains(script, "InterfaceAlias")
        assertContains(script, "Get-NetIPAddress -AddressFamily IPv4")
        assertContains(script, "SkipAsSource")
        assertContains(script, "Select-Object Alias, Index, IPv4Address")
    }

    @Test fun aliasIndexAndSourceMustBelongToTheSameStructuredDiscoveryResult() {
        assertEquals(PhysicalUpstream("Ethernet", 6, "192.168.0.10"), DesktopPhysicalInterface.parseNetwork(
            "\uFEFF{\"Alias\":\"Ethernet\",\"Index\":6,\"IPv4Address\":\"192.168.0.10\"}\r\n"))
        for (output in listOf("\"Ethernet\"", "{}", "null",
            "{\"Alias\":\"Ethernet\",\"Index\":\"6\",\"IPv4Address\":\"192.168.0.10\"}",
            "{\"Alias\":\"Ethernet\",\"Index\":0,\"IPv4Address\":\"192.168.0.10\"}",
            "{\"Alias\":\"Ethernet\",\"Index\":6,\"IPv4Address\":\"127.0.0.1\"}",
            "{\"Alias\":\"Ethernet\",\"Index\":6,\"IPv4Address\":\"169.254.0.1\"}",
            "{\"Alias\":\"Ethernet\",\"Index\":6,\"IPv4Address\":\"192.168.00.10\"}")) {
            assertNull(DesktopPhysicalInterface.parseNetwork(output), output)
        }
    }
}
