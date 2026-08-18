package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VpnProfileReachabilityTest {
    @Test
    fun parsesVlessDomainAndIpv6Endpoints() {
        assertEquals(
            VpnProfileEndpoint("vpn.example.com", 443),
            VpnProfileReachability.endpoint(
                VpnProfileConfig(
                    type = VpnProfileConfig.TYPE_VLESS,
                    uri = "vless://11111111-1111-1111-1111-111111111111@vpn.example.com:443?security=tls"
                )
            )
        )
        assertEquals(
            VpnProfileEndpoint("2001:db8::10", 8443),
            VpnProfileReachability.endpoint(
                VpnProfileConfig(
                    type = VpnProfileConfig.TYPE_VLESS,
                    rawConfig = "vless://id@[2001:db8::10]:8443#IPv6"
                )
            )
        )
    }

    @Test
    fun parsesWireGuardAndCompressedAwgEndpoints() {
        val config = """
            [Interface]
            PrivateKey = test

            [Peer]
            PublicKey = test
            Endpoint = 203.0.113.10:51820
        """.trimIndent()

        assertEquals(
            VpnProfileEndpoint("203.0.113.10", 51820),
            VpnProfileReachability.endpoint(
                VpnProfileConfig(
                    type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                    rawConfig = config
                )
            )
        )
        assertEquals(
            VpnProfileEndpoint("203.0.113.10", 51820),
            VpnProfileReachability.endpoint(
                VpnProfileConfig(
                    type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                    uri = compressedAwgUri("""{"config":${jsonString(config)}}""")
                )
            )
        )
    }

    @Test
    fun measuresTcpReachabilityForVless() {
        runBlocking {
            ServerSocket(0).use { server ->
                val profile = VpnProfileConfig(
                    type = VpnProfileConfig.TYPE_VLESS,
                    uri = "vless://id@127.0.0.1:${server.localPort}"
                )
                assertNotNull(VpnProfileReachability.ping(profile))
            }
        }
    }

    private fun compressedAwgUri(value: String): String {
        val deflater = Deflater()
        val output = ByteArrayOutputStream()
        try {
            deflater.setInput(value.toByteArray())
            deflater.finish()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        val payload = byteArrayOf(0, 0, 0, 0) + output.toByteArray()
        return "awg://${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}"
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}
