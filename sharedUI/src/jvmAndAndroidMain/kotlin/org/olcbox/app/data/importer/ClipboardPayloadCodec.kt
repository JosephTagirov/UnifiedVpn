package org.olcbox.app.data.importer

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

internal object ClipboardPayloadCodec {
    private const val PREFIX = "unifiedvpn+zlib:"
    private const val COMPRESSION_THRESHOLD_BYTES = 4 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024

    fun encode(text: String): String {
        val input = text.encodeToByteArray()
        if (input.size < COMPRESSION_THRESHOLD_BYTES) return text

        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = try {
            deflater.setInput(input)
            deflater.finish()
            ByteArrayOutputStream(input.size / 2).use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            deflater.end()
        }

        val encoded = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return encoded.takeIf { it.length < text.length } ?: text
    }

    fun decodeOrOriginal(value: String): String {
        if (!value.startsWith(PREFIX)) return value

        val compressed = Base64.getUrlDecoder().decode(value.removePrefix(PREFIX).trim())
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            ByteArrayOutputStream(compressed.size * 2).use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        require(output.size() <= MAX_DECOMPRESSED_BYTES) {
                            "Clipboard configuration is too large"
                        }
                    } else {
                        require(!inflater.needsDictionary() && !inflater.needsInput()) {
                            "Clipboard configuration is incomplete"
                        }
                    }
                }
                output.toByteArray().decodeToString()
            }
        } finally {
            inflater.end()
        }
    }
}
