package org.olcbox.app.data.importer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_IMPORTED_CONFIG_BYTES = 16 * 1024 * 1024

internal class ImportSourceTooLargeException(maxBytes: Int) :
    IOException("Configuration source exceeds $maxBytes bytes")

internal fun InputStream.readBoundedUtf8(
    maxBytes: Int = MAX_IMPORTED_CONFIG_BYTES
): String {
    require(maxBytes > 0) { "maxBytes must be positive" }

    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val remainingBytes = maxBytes - totalBytes
        val readLimit = minOf(buffer.size, remainingBytes + 1)
        val readBytes = read(buffer, 0, readLimit)
        if (readBytes < 0) break
        if (readBytes == 0) {
            val nextByte = read()
            if (nextByte < 0) break
            if (remainingBytes == 0) throw ImportSourceTooLargeException(maxBytes)
            output.write(nextByte)
            totalBytes += 1
            continue
        }
        if (readBytes > remainingBytes) throw ImportSourceTooLargeException(maxBytes)

        output.write(buffer, 0, readBytes)
        totalBytes += readBytes
    }

    return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
}
