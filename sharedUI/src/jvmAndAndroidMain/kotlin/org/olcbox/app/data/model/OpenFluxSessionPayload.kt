package org.olcbox.app.data.model

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// OpenFlux share v1 uses raw DEFLATE, not a zlib stream. Both compressed input
// and decoded JSON are bounded before the strict Session model is consulted.
@OptIn(ExperimentalEncodingApi::class)
internal actual fun decodeOpenFluxSessionPayload(encoded: String): String? = runCatching {
    require(encoded.length in 1..32768)
    val compact = encoded.filterNot(Char::isWhitespace)
    require(compact.all { it.isLetterOrDigit() || it in "-_/+=" })
    val padded = compact.replace('+', '-').replace('/', '_').padEnd((compact.length + 3) / 4 * 4, '=')
    val bytes = Base64.UrlSafe.decode(padded)
    val inflater = Inflater(true)
    try {
        inflater.setInput(bytes)
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(2048)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(output.size() + count <= 16384)
                if (count > 0) output.write(buffer, 0, count)
                else require(inflater.finished())
            }
            require(inflater.remaining == 0)
            output.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    } finally {
        inflater.end()
    }
}.getOrNull()

@OptIn(ExperimentalEncodingApi::class)
internal actual fun encodeOpenFluxSessionPayload(json: String): String {
    val input = json.encodeToByteArray()
    require(input.size in 1..16384)
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    return try {
        deflater.setInput(input)
        deflater.finish()
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(2048)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0 || deflater.finished())
                output.write(buffer, 0, count)
            }
            Base64.UrlSafe.encode(output.toByteArray()).trimEnd('=')
        }
    } finally {
        deflater.end()
    }
}
