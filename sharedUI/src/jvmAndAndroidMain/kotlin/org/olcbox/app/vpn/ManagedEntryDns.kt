package org.olcbox.app.vpn

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Locale

/** Only the dedicated owned entry is resolved here. An unavailable DNS server cannot disable the backup. */
internal object ManagedEntryDns {
    const val PRIMARY_HOST = "ru1-kvn.meesharr.space"
    const val FALLBACK_PRIMARY_IP = "178.208.70.181"
    const val BACKUP_IP = "48.103.218.29"
    const val QUERY_TIMEOUT_MS = 1_200
    private val servers = listOf("1.1.1.1", "8.8.8.8")
    private val random = SecureRandom()

    fun resolve(exchange: (server: String, request: ByteArray) -> ByteArray?): String {
        for (server in servers) {
            val query = query(random.nextInt(65536))
            val response = runCatching { exchange(server, query.copyOf()) }.getOrNull() ?: continue
            addresses(query, response).firstOrNull { it != BACKUP_IP }?.let { return it }
        }
        return FALLBACK_PRIMARY_IP
    }

    internal fun query(id: Int): ByteArray {
        require(id in 0..65535)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (value in listOf(id, 0x0100, 1, 0, 0, 0)) out.writeShort(value)
            for (label in PRIMARY_HOST.split('.')) {
                out.writeByte(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.writeByte(0)
            out.writeShort(1)
            out.writeShort(1)
        }
        return bytes.toByteArray()
    }

    internal fun addresses(query: ByteArray, response: ByteArray): List<String> = runCatching {
        require(response.size in 12..4096 && query.size >= 12)
        fun word(offset: Int): Int {
            require(offset >= 0 && offset + 2 <= response.size)
            return ((response[offset].toInt() and 255) shl 8) or (response[offset + 1].toInt() and 255)
        }
        require(response[0] == query[0] && response[1] == query[1])
        val flags = word(2)
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0200 == 0 && flags and 15 == 0)
        require(word(4) == 1)
        val answers = word(6)
        val total = answers + word(8) + word(10)
        require(answers in 1..128 && total <= 128)
        fun name(start: Int): Pair<String, Int> {
            var position = start
            var end: Int? = null
            val labels = mutableListOf<String>()
            val visited = mutableSetOf<Int>()
            repeat(128) {
                require(position in response.indices && visited.add(position))
                val length = response[position].toInt() and 255
                if (length and 0xc0 == 0xc0) {
                    require(position + 1 < response.size)
                    if (end == null) end = position + 2
                    position = ((length and 63) shl 8) or (response[position + 1].toInt() and 255)
                } else {
                    require(length <= 63)
                    position++
                    if (length == 0) {
                        val result = labels.joinToString(".").lowercase(Locale.ROOT)
                        require(result.isNotEmpty() && result.length <= 253)
                        return result to (end ?: position)
                    }
                    require(position + length <= response.size)
                    val label = response.copyOfRange(position, position + length).toString(Charsets.US_ASCII)
                    require(label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
                    labels += label
                    position += length
                }
            }
            error("DNS name exceeds its bound")
        }
        val question = name(12)
        require(question.first == PRIMARY_HOST && word(question.second) == 1 && word(question.second + 2) == 1)
        var position = question.second + 4
        val aliases = mutableMapOf<String, String>()
        val values = mutableMapOf<String, MutableList<String>>()
        repeat(total) { index ->
            val owner = name(position)
            position = owner.second
            require(position + 10 <= response.size)
            val type = word(position)
            val klass = word(position + 2)
            val length = word(position + 8)
            position += 10
            val end = position + length
            require(end <= response.size)
            if (index < answers && klass == 1) when (type) {
                1 -> {
                    require(length == 4)
                    val address = response.copyOfRange(position, end).joinToString(".") { (it.toInt() and 255).toString() }
                    if (isPublicIpv4(address)) values.getOrPut(owner.first) { mutableListOf() }.add(address)
                }
                5 -> {
                    val target = name(position)
                    require(target.second == end)
                    require(aliases[owner.first] == null || aliases[owner.first] == target.first)
                    aliases[owner.first] = target.first
                }
            }
            position = end
        }
        require(position == response.size)
        var owner = PRIMARY_HOST
        val visited = mutableSetOf<String>()
        repeat(16) {
            require(visited.add(owner))
            values[owner]?.takeIf { it.isNotEmpty() }?.let { return@runCatching it.distinct() }
            owner = aliases[owner] ?: return@runCatching emptyList()
        }
        emptyList()
    }.getOrDefault(emptyList())

    internal fun isPublicIpv4(address: String): Boolean {
        val parts = address.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b, c) = parts
        return a !in setOf(0, 10, 127) && a < 224 &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 && c in setOf(0, 2))) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
    }
}
