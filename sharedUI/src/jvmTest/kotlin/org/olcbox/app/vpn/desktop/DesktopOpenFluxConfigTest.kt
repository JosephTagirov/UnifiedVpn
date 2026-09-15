package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DesktopOpenFluxConfigTest {
    @Test
    fun buildsEncryptedClientWithSelectedLocalSocksSettings() {
        val imported = config().toJson()
        val generated = Json.parseToJsonElement(
            DesktopOpenFluxConfig.build(
                profile = profile(imported),
                socks = DesktopSocksProxySettings(
                    port = 11980,
                    username = "local-user",
                    password = "local-password"
                )
            )
        ).jsonObject

        assertEquals("1", generated.getValue("version").jsonPrimitive.content)
        assertEquals("client", generated.getValue("mode").jsonPrimitive.content)
        assertEquals("yandex", generated.getValue("transport").jsonPrimitive.content)
        assertEquals(DOCUMENT_URL, generated.getValue("document_url").jsonPrimitive.content)
        assertEquals(ENCRYPTION_KEY, generated.getValue("encryption_key").jsonPrimitive.content)
        assertEquals("127.0.0.1:11980", generated.getValue("socks5").jsonPrimitive.content)
        assertEquals("local-user", generated.getValue("socks_username").jsonPrimitive.content)
        assertEquals("local-password", generated.getValue("socks_password").jsonPrimitive.content)
        assertEquals("60", generated.getValue("handshake_timeout_seconds").jsonPrimitive.content)
        assertFalse("allow_plaintext" in generated)
    }

    @Test
    fun acceptsAnImportedUriWhenRawConfigIsBlank() {
        val uri = config().toUri()
        val generated = Json.parseToJsonElement(
            DesktopOpenFluxConfig.build(
                profile = VpnProfileConfig(type = "OpenFlux", rawConfig = "  ", uri = uri),
                socks = DesktopSocksProxySettings(port = 11981)
            )
        ).jsonObject

        assertEquals(DOCUMENT_URL, generated.getValue("document_url").jsonPrimitive.content)
        assertEquals(ENCRYPTION_KEY, generated.getValue("encryption_key").jsonPrimitive.content)
        assertEquals("127.0.0.1:11981", generated.getValue("socks5").jsonPrimitive.content)
    }

    @Test
    fun commandLineContainsOnlyThePrivateConfigPath() {
        val binary = Path.of("test runtime", "openflux-windows-amd64.exe")
        val privateConfig = Path.of("test runtime", "openflux-private.json")
        val command = DesktopOpenFluxConfig.args(binary, privateConfig)

        assertEquals(
            listOf(binary.toAbsolutePath().toString(), "--config", privateConfig.toAbsolutePath().toString()),
            command
        )
        assertFalse(command.any { ENCRYPTION_KEY in it || DOCUMENT_URL in it })
    }

    @Test
    fun rejectsMissingKeysAndWrongProfileTypes() {
        assertFails {
            DesktopOpenFluxConfig.build(profile("{}"), DesktopSocksProxySettings())
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopOpenFluxConfig.build(
                VpnProfileConfig(type = VpnProfileConfig.TYPE_VLESS, rawConfig = config().toJson()),
                DesktopSocksProxySettings()
            )
        }
    }

    @Test
    fun refusesExternalSocksListenersAndIncompleteCredentials() {
        val profile = profile(config().toJson())
        listOf("0.0.0.0", "192.0.2.10", "example.invalid").forEach { host ->
            assertFailsWith<IllegalArgumentException> {
                DesktopOpenFluxConfig.build(profile, DesktopSocksProxySettings(host = host))
            }
        }
        listOf(
            DesktopSocksProxySettings(username = "user"),
            DesktopSocksProxySettings(password = "password")
        ).forEach { settings ->
            assertFailsWith<IllegalArgumentException> {
                DesktopOpenFluxConfig.build(profile, settings)
            }
        }
    }

    private fun config() = OpenFluxProfileConfig(
        documentUrl = DOCUMENT_URL,
        encryptionKey = ENCRYPTION_KEY
    )

    private fun profile(raw: String) = VpnProfileConfig(
        type = VpnProfileConfig.TYPE_OPENFLUX,
        rawConfig = raw
    )

    private companion object {
        const val DOCUMENT_URL = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Ftest-document"
        val ENCRYPTION_KEY = "ab".repeat(32)
    }
}
