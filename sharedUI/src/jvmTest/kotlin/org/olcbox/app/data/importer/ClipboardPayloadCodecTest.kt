package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ClipboardPayloadCodecTest {
    @Test
    fun largeBundleIsCompressedAndRestored() {
        val source = buildString {
            append("{\"version\":5,\"locations\":[")
            repeat(2_000) { index ->
                append("{\"storage_id\":\"profile-$index\",\"raw_config\":\"")
                append("vless://user@example.test:443?security=reality&type=tcp")
                append("\"},")
            }
            append("]}")
        }

        val encoded = ClipboardPayloadCodec.encode(source)

        assertTrue(encoded.startsWith("unifiedvpn+zlib:"))
        assertTrue(encoded.length < source.length / 2)
        assertEquals(source, ClipboardPayloadCodec.decodeOrOriginal(encoded))
    }

    @Test
    fun shortAndOrdinaryClipboardTextIsUnchanged() {
        val source = "vless://example.test/profile"
        assertEquals(source, ClipboardPayloadCodec.encode(source))
        assertEquals(source, ClipboardPayloadCodec.decodeOrOriginal(source))
    }

    @Test
    fun malformedCompressedPayloadIsRejected() {
        assertFails {
            ClipboardPayloadCodec.decodeOrOriginal("unifiedvpn+zlib:not-base64")
        }
    }
}
