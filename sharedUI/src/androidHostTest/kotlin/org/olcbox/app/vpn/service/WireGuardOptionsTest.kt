package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WireGuardOptionsTest {
    @Test
    fun explicitAwg31FlagsBecomeBooleansAndKeepaliveRangeIsPreserved() {
        val result = parseWireGuardConfig(config("RandomTrailers = true\nDisableCookies = false", "22-30"))
        assertEquals(true, result.amneziaWireGuardFields["random_trailers"])
        assertEquals(false, result.amneziaWireGuardFields["disable_cookies"])
        assertEquals("22-30", result.persistentKeepalive)
    }

    @Test
    fun legacyProfileDoesNotAcquireAwg31Options() {
        val result = parseWireGuardConfig(config("Jc = 4", "25"))
        assertEquals(4L, result.amneziaWireGuardFields["jc"])
        assertFalse(result.amneziaWireGuardFields.containsKey("random_trailers"))
        assertFalse(result.amneziaWireGuardFields.containsKey("disable_cookies"))
        assertEquals("25", result.persistentKeepalive)
        assertNull(parseWireGuardConfig(config()).persistentKeepalive)
    }

    @Test
    fun invalidExplicitFlagsAndKeepaliveFailInsteadOfBeingDropped() {
        for (field in listOf("RandomTrailers", "DisableCookies")) {
            assertFailsWith<IllegalArgumentException> { parseWireGuardConfig(config("$field = invalid")) }
        }
        assertFailsWith<IllegalArgumentException> { parseWireGuardConfig(config(keepalive = "30-20")) }
    }

    private fun config(options: String = "", keepalive: String? = null): String = """
        [Interface]
        Address = 10.8.1.2/32
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        $options
        [Peer]
        PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
        Endpoint = 203.0.113.42:55424
        ${keepalive?.let { "PersistentKeepalive = $it" }.orEmpty()}
    """.trimIndent()
}
