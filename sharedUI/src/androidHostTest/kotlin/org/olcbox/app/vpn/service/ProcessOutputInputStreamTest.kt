package org.olcbox.app.vpn.service

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessOutputInputStreamTest {
    @Test
    fun retriesInterruptedScalarReadAndPreservesEof() {
        val source = InterruptingInput("ok", 2)
        ProcessOutputInputStream(source).use {
            assertEquals('o'.code, it.read())
            assertEquals('k'.code, it.read())
            assertEquals(-1, it.read())
        }
        assertTrue(source.closed)
    }

    @Test
    fun retriesBeforeDecoderWithoutLosingPartialReadinessLine() {
        val text = "OPENFLUX_ENCRYPTION AES-256-GCM\nOPENFLUX_READY\n"
        val source = object : InputStream() {
            var position = 0
            var interrupted = false
            override fun read(): Int {
                if (position == 36 && !interrupted) {
                    interrupted = true
                    throw InterruptedIOException("read interrupted")
                }
                return if (position == text.length) -1 else text[position++].code
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val value = read()
                if (value != -1) bytes[offset] = value.toByte()
                return if (value == -1) -1 else 1
            }
        }
        val lines = mutableListOf<String>()
        consumeOpenFluxEngineOutput(source, lines::add)
        assertEquals(listOf("OPENFLUX_ENCRYPTION AES-256-GCM", "OPENFLUX_READY"), lines)
    }

    @Test
    fun ordinaryIoFailureIsNotRetried() {
        val failure = IOException("Bad file descriptor")
        var calls = 0
        val source = object : InputStream() {
            override fun read(): Int { calls++; throw failure }
        }
        assertSame(failure, assertFailsWith<IOException> { ProcessOutputInputStream(source).read() })
        assertEquals(1, calls)
    }

    @Test
    fun signalStormIsBounded() {
        val source = InterruptingInput("", 100)
        assertFailsWith<InterruptedIOException> { ProcessOutputInputStream(source).read() }
        assertEquals(16, source.calls)
    }

    @Test
    fun canceledReaderDoesNotReadOrClearInterrupt() {
        val source = InterruptingInput("data", 0)
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedIOException> { ProcessOutputInputStream(source).read() }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, source.calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun partialTransferIsNotRetried() {
        assertFalse(isSignalInterruptedRead(InterruptedIOException().apply { bytesTransferred = 1 }))
    }

    @Test
    fun recognizesTypedNestedInterruptionNotMessageText() {
        assertTrue(isSignalInterruptedRead(IOException("wrapped", InterruptedIOException())))
        assertFalse(isSignalInterruptedRead(IOException("read failed: EINTR")))
    }

    private class InterruptingInput(text: String, private var interruptions: Int) : InputStream() {
        private val bytes = ByteArrayInputStream(text.toByteArray())
        var calls = 0
        var closed = false
        override fun read(): Int {
            calls++
            if (interruptions-- > 0) throw InterruptedIOException("read interrupted")
            return bytes.read()
        }
        override fun close() { closed = true; bytes.close() }
    }
}
