package org.olcbox.app.vpn.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.vpn.XraySocksSettings
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

/** Authenticates SOCKS directly, avoiding Java's process-wide proxy Authenticator and its mutex. */
internal object EntryFailoverHealthProbe {
    suspend fun healthy(proxy: XraySocksSettings): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        coroutineScope {
            val google = async { request(proxy, "www.google.com", "/generate_204", 204) }
            val cloudflare = async {
                delay(500)
                request(proxy, "www.cloudflare.com", "/cdn-cgi/trace", 200)
            }
            try {
                select {
                    google.onAwait { if (it) true else cloudflare.await() }
                    cloudflare.onAwait { if (it) true else google.await() }
                }
            } finally {
                google.cancel()
                cloudflare.cancel()
            }
        }
    } ?: false

    private suspend fun request(proxy: XraySocksSettings, host: String, path: String, expectedStatus: Int): Boolean =
        suspendCancellableCoroutine { continuation ->
            val socket = Socket()
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                val healthy = try {
                    socket.use raw@{
                        if (!continuation.isActive) return@raw false
                        val deadline = (System.nanoTime() / 1_000_000L) + PROBE_TIMEOUT_MS
                        socket.connect(InetSocketAddress(proxy.host, proxy.port), remaining(deadline))
                        socket.soTimeout = remaining(deadline)
                        connectSocks(socket, proxy, host)
                        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, host, 443, true) as SSLSocket
                        tls.use secure@{
                            tls.soTimeout = remaining(deadline)
                            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            tls.startHandshake()
                            tls.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\n" +
                                "User-Agent: UnifiedVPN-Health/1\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            tls.outputStream.flush()
                            val input = tls.inputStream
                            val status = line(input, tls, deadline, 1024)?.split(' ')?.getOrNull(1)?.toIntOrNull()
                            if (status != expectedStatus) return@secure false
                            var headerBytes = 0
                            while (true) {
                                val header = line(input, tls, deadline, 4096) ?: return@secure false
                                headerBytes += header.length + 2
                                if (headerBytes > 16_384) return@secure false
                                if (header.isEmpty()) break
                            }
                            if (expectedStatus == 204) return@secure true
                            // A successful TLS handshake plus the expected trace field proves an HTTP data path.
                            var bodyBytes = 0
                            while (bodyBytes < 4096) {
                                val bodyLine = line(input, tls, deadline, 1024) ?: break
                                bodyBytes += bodyLine.length + 1
                                if (bodyLine.startsWith("ip=") && bodyLine.length > 3) return@secure true
                            }
                            false
                        }
                    }
                } catch (_: Exception) { false }
                if (continuation.isActive) continuation.resume(healthy)
            })
        }

    private fun connectSocks(socket: Socket, proxy: XraySocksSettings, host: String) {
        require(proxy.username.isNotEmpty() && proxy.password.isNotEmpty())
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        output.flush()
        require(input.byte() == 5 && input.byte() == 2) { "SOCKS authentication unavailable" }
        val user = proxy.username.toByteArray(Charsets.UTF_8)
        val password = proxy.password.toByteArray(Charsets.UTF_8)
        require(user.size in 1..255 && password.size in 1..255)
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
        output.flush()
        require(input.byte() == 1 && input.byte() == 0) { "SOCKS authentication rejected" }
        val name = host.toByteArray(Charsets.US_ASCII)
        output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(1, -69)) // 443
        output.flush()
        require(input.byte() == 5 && input.byte() == 0 && input.byte() == 0) { "SOCKS connect failed" }
        val addressLength = when (input.byte()) { 1 -> 4; 4 -> 16; 3 -> input.byte(); else -> error("Invalid SOCKS address") }
        repeat(addressLength + 2) { input.byte() }
    }

    private fun line(input: InputStream, socket: Socket, deadline: Long, maximum: Int): String? {
        val result = StringBuilder()
        repeat(maximum) {
            socket.soTimeout = remaining(deadline)
            val value = input.read()
            if (value < 0) return result.toString().takeIf { it.isNotEmpty() }
            if (value == 10) return result.toString().removeSuffix("\r")
            result.append(value.toChar())
        }
        error("HTTP probe line is too long")
    }

    private fun remaining(deadline: Long): Int =
        (deadline - (System.nanoTime() / 1_000_000L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().also {
            require(it > 0) { "HTTPS probe deadline exceeded" }
        }

    private fun InputStream.byte(): Int = read().also { if (it < 0) throw EOFException() }
    private const val PROBE_TIMEOUT_MS = 6_000L
}
