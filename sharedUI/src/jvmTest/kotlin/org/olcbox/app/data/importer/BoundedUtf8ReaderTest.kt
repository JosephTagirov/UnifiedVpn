package org.olcbox.app.data.importer

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoundedUtf8ReaderTest {
    @Test
    fun readsSourceAtLimitBeforeUtf8Decode() {
        val content = "config"

        val decoded = ByteArrayInputStream(content.encodeToByteArray())
            .readBoundedUtf8(maxBytes = content.length)

        assertEquals(content, decoded)
    }

    @Test
    fun rejectsSourceBeyondLimit() {
        assertFailsWith<ImportSourceTooLargeException> {
            ByteArrayInputStream("config".encodeToByteArray()).readBoundedUtf8(maxBytes = 5)
        }
    }

    @Test
    fun jvmImporterRejectsOversizedFileBeforeAllocation() = runTest {
        val path = Files.createTempFile("unifiedvpn-import-limit", ".txt")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.setLength(MAX_IMPORTED_CONFIG_BYTES.toLong() + 1L)
            }

            assertNull(JvmConfigImporter().readTextFromSource(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
