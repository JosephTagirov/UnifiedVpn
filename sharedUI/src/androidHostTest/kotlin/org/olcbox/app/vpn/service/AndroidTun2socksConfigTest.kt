package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AndroidTun2socksConfigTest {
    @Test
    fun generatedConfigKeepsUpstreamDownloadCapacityForEverySocksEndpoint() {
        for (port in listOf(10818, 10858)) {
            val config = buildAndroidTun2socksConfig(
                mtu = 1500,
                ipv4Address = "198.18.0.1",
                socksHost = "127.0.0.1",
                socksPort = port,
                username = "synthetic-user",
                password = "synthetic-password"
            )

            // A 4 KiB ACK-retained ring limits download independently of upload.
            assertContains(config, "tcp-buffer-size: 65536")
            assertFalse(config.contains("tcp-buffer-size: 4096"))
            assertContains(config, "port: $port")
            assertContains(config, "mtu: 1500")
            assertContains(config, "udp: 'udp'")
        }
    }
}
