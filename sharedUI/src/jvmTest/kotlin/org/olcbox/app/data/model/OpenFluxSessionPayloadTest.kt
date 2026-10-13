package org.olcbox.app.data.model

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertNull

class OpenFluxSessionPayloadTest {
    @Test
    fun rejectsCompressedBombTruncationTrailingBytesAndInvalidUtf8() {
        val compressed = compress("{}".encodeToByteArray())
        for (bytes in listOf(
            compress(ByteArray(16385) { 'x'.code.toByte() }),
            compressed.copyOf(compressed.size - 1), compressed + byteArrayOf(0),
            compress(byteArrayOf(0xff.toByte()))
        )) {
            assertNull(decodeOpenFluxSessionPayload(Base64.getUrlEncoder().encodeToString(bytes)))
        }
    }

    private fun compress(input: ByteArray): ByteArray {
        val deflater = Deflater(9, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(2048)
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
                output.toByteArray()
            }
        } finally {
            deflater.end()
        }
    }
}
