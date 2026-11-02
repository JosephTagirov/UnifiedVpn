package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VlessUriOptionsTest {
    @Test
    fun preservesNestedXhttpOptions() {
        val extra = """{"headers":{"X-Token":"test"},"xmux":{"maxConcurrency":"16-32"},"downloadSettings":{"address":"download.example","port":443}}"""
        assertEquals<JsonElement?>(Json.parseToJsonElement(extra), parseXhttpExtra(extra))
    }

    @Test
    fun rejectsInvalidExtraWithoutExposingItsContent() {
        for (extra in listOf("[1]", "null", "{\"private-token\":")) {
            val error = assertFailsWith<IllegalArgumentException> { parseXhttpExtra(extra) }
            assertEquals("Invalid XHTTP extra settings: expected a JSON object", error.message)
        }
    }

    @Test
    fun acceptsOmittedExtra() {
        assertNull(parseXhttpExtra(null))
        assertNull(parseXhttpExtra(" "))
    }

    @Test
    fun schemeIsCaseInsensitiveAndPayloadIsPreserved() {
        for (scheme in listOf("vless://", "VLESS://", "VlEsS://")) {
            assertEquals("uuid@host:443?path=%2FCase", vlessUriPayload(scheme + "uuid@host:443?path=%2FCase"))
        }
        assertFailsWith<IllegalArgumentException> { vlessUriPayload("https://host") }
    }
}
