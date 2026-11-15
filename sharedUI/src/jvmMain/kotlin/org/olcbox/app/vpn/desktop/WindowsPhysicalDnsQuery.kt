package org.olcbox.app.vpn.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.WindowsTrustedExecutables

/** A DNS datagram owns its socket and never consults the captured system resolver. */
internal object WindowsPhysicalDnsQuery {
    private const val QUERY_TIMEOUT_MS = 1_200
    private const val MAX_RESPONSE_BYTES = 4_096
    internal const val IPPROTO_IP = 0
    internal const val IP_UNICAST_IF = 31
    internal const val SOL_SOCKET = 0xffff
    internal const val SO_SNDTIMEO = 0x1005
    internal const val SO_RCVTIMEO = 0x1006
    private val providers = setOf("1.1.1.1", "8.8.8.8")

    fun exchange(upstream: PhysicalUpstream, server: String, request: ByteArray): ByteArray? {
        if (DesktopPaths.os != DesktopOs.Windows) return null
        return runCatching { exchange(upstream, server, request, NativePhysicalDnsSocketApi()) }.getOrNull()
    }

    internal fun exchange(
        upstream: PhysicalUpstream,
        server: String,
        request: ByteArray,
        api: PhysicalDnsSocketApi,
        nanoTime: () -> Long = System::nanoTime,
    ): ByteArray? {
        if (upstream.index <= 0 || upstream.alias.isBlank() || physicalIpv4Bytes(upstream.ipv4Address) == null ||
            server !in providers || request.size !in 12..512 || Thread.currentThread().isInterrupted) return null
        val deadline = nanoTime() + QUERY_TIMEOUT_MS * 1_000_000L
        fun remainingMillis(): Int? {
            if (Thread.currentThread().isInterrupted) return null
            val remaining = deadline - nanoTime()
            return if (remaining <= 0) null else ((remaining + 999_999L) / 1_000_000L).coerceAtMost(QUERY_TIMEOUT_MS.toLong()).toInt()
        }
        var started = false
        var socket: Long? = null
        try {
            if (!api.startup()) return null
            started = true
            if (remainingMillis() == null) return null
            val owned = api.createUdpSocket() ?: return null
            socket = owned
            // Winsock defines this DWORD as network byte order, unlike its timeout DWORDs.
            // https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options
            val interfaceBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(upstream.index).array()
            // getsockopt returns this same interface index in HOST byte order.
            val interfaceReadback = nativeDword(upstream.index)
            if (!api.setOption(owned, IPPROTO_IP, IP_UNICAST_IF, interfaceBytes)) return null
            if (api.getOption(owned, IPPROTO_IP, IP_UNICAST_IF)?.contentEquals(interfaceReadback) != true) return null
            if (!api.bind(owned, upstream.ipv4Address)) return null
            if (!matchesBinding(api.localAddress(owned), upstream)) return null
            if (remainingMillis() == null || !api.connect(owned, server, 53)) return null
            if (!matchesBinding(api.localAddress(owned), upstream)) return null
            if (api.getOption(owned, IPPROTO_IP, IP_UNICAST_IF)?.contentEquals(interfaceReadback) != true) return null
            val sendTimeout = remainingMillis() ?: return null
            if (!api.setOption(owned, SOL_SOCKET, SO_SNDTIMEO, nativeDword(sendTimeout))) return null
            if (api.send(owned, request) != request.size) return null
            val receiveTimeout = remainingMillis() ?: return null
            if (!api.setOption(owned, SOL_SOCKET, SO_RCVTIMEO, nativeDword(receiveTimeout))) return null
            val response = api.receive(owned, MAX_RESPONSE_BYTES) ?: return null
            if (remainingMillis() == null || response.size !in 12..MAX_RESPONSE_BYTES) return null
            return response
        } catch (_: Exception) {
            return null
        } finally {
            socket?.let { runCatching { api.close(it) } }
            if (started) runCatching { api.cleanup() }
        }
    }

    private fun matchesBinding(address: PhysicalDnsBoundAddress?, upstream: PhysicalUpstream): Boolean =
        address != null && address.ipv4Address == upstream.ipv4Address && address.port in 1..65535

    private fun nativeDword(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()
}

internal data class PhysicalDnsBoundAddress(val ipv4Address: String, val port: Int)

/** Raw option bytes stay visible to the lifecycle tests, including the interface byte order. */
internal interface PhysicalDnsSocketApi {
    fun startup(): Boolean
    fun createUdpSocket(): Long?
    fun setOption(socket: Long, level: Int, option: Int, value: ByteArray): Boolean
    fun getOption(socket: Long, level: Int, option: Int): ByteArray?
    fun bind(socket: Long, source: String): Boolean
    fun localAddress(socket: Long): PhysicalDnsBoundAddress?
    fun connect(socket: Long, server: String, port: Int): Boolean
    fun send(socket: Long, request: ByteArray): Int
    fun receive(socket: Long, maximum: Int): ByteArray?
    fun close(socket: Long)
    fun cleanup()
}

internal class NativePhysicalDnsSocketApi : PhysicalDnsSocketApi {
    private val winsock = Native.load(WindowsTrustedExecutables.winsockLibraryPath().toString(), WinsockApi::class.java)

    override fun startup(): Boolean = Memory(512).use { data ->
        val result = winsock.WSAStartup(0x0202.toShort(), data)
        if (result != 0) return false
        if ((data.getShort(0).toInt() and 0xffff) != 0x0202) {
            winsock.WSACleanup()
            return false
        }
        true
    }

    override fun createUdpSocket(): Long? {
        val value = Pointer.nativeValue(winsock.socket(2, 2, 17))
        val invalid = if (Native.POINTER_SIZE == 8) -1L else 0xffffffffL
        return value.takeUnless { it == invalid }
    }

    override fun setOption(socket: Long, level: Int, option: Int, value: ByteArray): Boolean =
        Memory(value.size.toLong()).use { data ->
            data.write(0, value, 0, value.size)
            winsock.setsockopt(Pointer(socket), level, option, data, value.size) == 0
        }

    override fun getOption(socket: Long, level: Int, option: Int): ByteArray? = Memory(4).use { data ->
        val size = IntByReference(4)
        if (winsock.getsockopt(Pointer(socket), level, option, data, size) != 0 || size.value != 4) null
        else data.getByteArray(0, 4)
    }

    override fun bind(socket: Long, source: String): Boolean = address(source, 0).use { data ->
        winsock.bind(Pointer(socket), data, 16) == 0
    }

    override fun localAddress(socket: Long): PhysicalDnsBoundAddress? = Memory(16).use { data ->
        val size = IntByReference(16)
        if (winsock.getsockname(Pointer(socket), data, size) != 0 || size.value != 16 || data.getShort(0).toInt() != 2) return null
        val bytes = data.getByteArray(0, 16)
        PhysicalDnsBoundAddress(bytes.sliceArray(4..7).joinToString(".") { (it.toInt() and 255).toString() },
            ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255))
    }

    override fun connect(socket: Long, server: String, port: Int): Boolean = address(server, port).use { data ->
        winsock.connect(Pointer(socket), data, 16) == 0
    }

    override fun send(socket: Long, request: ByteArray): Int = Memory(request.size.toLong()).use { data ->
        data.write(0, request, 0, request.size)
        winsock.send(Pointer(socket), data, request.size, 0)
    }

    override fun receive(socket: Long, maximum: Int): ByteArray? = Memory(maximum.toLong()).use { data ->
        val count = winsock.recv(Pointer(socket), data, maximum, 0)
        if (count <= 0 || count > maximum) null else data.getByteArray(0, count)
    }

    override fun close(socket: Long) { winsock.closesocket(Pointer(socket)) }
    override fun cleanup() { winsock.WSACleanup() }

    private fun address(ipv4: String, port: Int): Memory = Memory(16).apply {
        clear()
        setShort(0, 2.toShort())
        setByte(2, (port ushr 8).toByte())
        setByte(3, port.toByte())
        val bytes = requireNotNull(physicalIpv4Bytes(ipv4))
        write(4, bytes, 0, 4)
    }

    private interface WinsockApi : StdCallLibrary {
        fun WSAStartup(version: Short, data: Pointer): Int
        fun WSACleanup(): Int
        fun socket(family: Int, type: Int, protocol: Int): Pointer?
        fun setsockopt(socket: Pointer, level: Int, option: Int, value: Pointer, length: Int): Int
        fun getsockopt(socket: Pointer, level: Int, option: Int, value: Pointer, length: IntByReference): Int
        fun bind(socket: Pointer, address: Pointer, length: Int): Int
        fun getsockname(socket: Pointer, address: Pointer, length: IntByReference): Int
        fun connect(socket: Pointer, address: Pointer, length: Int): Int
        fun send(socket: Pointer, bytes: Pointer, count: Int, flags: Int): Int
        fun recv(socket: Pointer, bytes: Pointer, count: Int, flags: Int): Int
        fun closesocket(socket: Pointer): Int
    }
}
