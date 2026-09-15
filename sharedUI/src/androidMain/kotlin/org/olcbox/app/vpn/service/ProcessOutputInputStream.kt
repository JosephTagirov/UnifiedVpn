package org.olcbox.app.vpn.service

import android.system.ErrnoException
import android.system.OsConstants
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

// Signals can interrupt Android pipe reads without canceling the reader thread.
internal class ProcessOutputInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = readWithSignalRetry { `in`.read() }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        readWithSignalRetry { `in`.read(bytes, offset, length) }

    private inline fun readWithSignalRetry(read: () -> Int): Int {
        var interruptions = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Reader was canceled")
            try {
                return read()
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted || !isSignalInterruptedRead(failure) ||
                    ++interruptions >= MAX_INTERRUPTED_READS
                ) throw failure
                Thread.yield()
            }
        }
    }
}

internal fun isSignalInterruptedRead(failure: IOException): Boolean {
    var cause: Throwable? = failure
    repeat(8) {
        val current = cause ?: return false
        if (current is InterruptedIOException) return current.bytesTransferred == 0
        if (current is ErrnoException) return current.errno == OsConstants.EINTR
        cause = current.cause?.takeUnless { it === current }
    }
    return false
}

private const val MAX_INTERRUPTED_READS = 16
