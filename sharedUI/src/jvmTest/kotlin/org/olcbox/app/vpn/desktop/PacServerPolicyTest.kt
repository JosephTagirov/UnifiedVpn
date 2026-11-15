package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacServerPolicyTest {
    @Test fun runningServerChangesPolicyWithTheSelectedSubscription() {
        val server = PacServer(port = 0)
        try {
            server.start(directRules = listOf("domain:first.fixture.test"))
            val port = server.boundPort
            assertContains(server.currentPacContent(), "first.fixture.test")
            server.start(directRules = listOf("domain:second.fixture.test"))
            assertEquals(port, server.boundPort)
            assertContains(server.currentPacContent(), "second.fixture.test")
            assertTrue("first.fixture.test" !in server.currentPacContent())
        } finally { server.stop() }
    }

    @Test fun usesEscapedPolicyDataWithoutInventingATldWideBypass() {
        val pac = PacServer.generatePacWithRules(directRules = listOf("domain:bank.example.com", "full:media.example.ru", "regexp:^news[0-9]+\\.example\\.com$"))
        assertContains(pac, "policySuffixes = [\"bank.example.com\"]")
        assertContains(pac, "policyFullNames = [\"media.example.ru\"]")
        assertTrue("\"ru\", \"su\"" !in pac)
        assertContains(pac, "localAddress(host)")
    }

    @Test fun nativeJavascriptDistinguishesSuffixFullAndRegexRulesAndKeepsLanDirect() {
        val node = System.getenv("NODE_BINARY") ?: return
        val script = Files.createTempFile("unified-pac-policy-", ".js")
        try {
            val pac = PacServer.generatePacWithRules(directRules = listOf("domain:bank.example.com", "full:media.example.ru", "regexp:^news[0-9]+\\.example\\.com$"))
            val hosts = listOf("bank.example.com", "pay.bank.example.com", "evilbank.example.com", "media.example.ru",
                "sub.media.example.ru", "news12.example.com", "news.example.com", "unlisted.ru", "unlisted.su", "10.0.0.1")
            val input = JsonArray(hosts.map(::JsonPrimitive))
            Files.writeString(script, "function isPlainHostName(host) { return host.indexOf('.') < 0; }\n" + pac +
                "\nconsole.log(JSON.stringify($input.map(function(host) { return FindProxyForURL('https://' + host + '/', host); })));\n")
            val process = ProcessBuilder(node, script.toString()).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                val proxy = "SOCKS5 127.0.0.1:10808"
                val expected = listOf("DIRECT", "DIRECT", proxy, "DIRECT", proxy, "DIRECT", proxy, proxy, proxy, "DIRECT")
                assertEquals(JsonArray(expected.map(::JsonPrimitive)).toString(), process.inputStream.bufferedReader().readText().trim())
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) } }
        } finally { Files.deleteIfExists(script) }
    }
}
