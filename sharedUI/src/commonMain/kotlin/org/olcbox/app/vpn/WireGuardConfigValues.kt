package org.olcbox.app.vpn

internal object WireGuardConfigValues {
    fun boolean(value: String, field: String): Boolean = when (unquote(value).lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> throw IllegalArgumentException("Invalid $field boolean")
    }

    fun keepalive(value: String): String {
        val match = KEEPALIVE.matchEntire(unquote(value))
            ?: throw IllegalArgumentException("Invalid WireGuard PersistentKeepalive value")
        val minimum = match.groupValues[1].toLongOrNull()
        val maximum = if (match.groupValues[2].isEmpty()) minimum else match.groupValues[2].toLongOrNull()
        require(minimum != null && maximum != null && minimum in 0..UINT_MAX && maximum in minimum..UINT_MAX) {
            "Invalid WireGuard PersistentKeepalive range"
        }
        return if (match.groupValues[2].isEmpty()) minimum.toString() else "$minimum-$maximum"
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")

    private val KEEPALIVE = Regex("([0-9]+)(?:-([0-9]+))?")
    private const val UINT_MAX = 4_294_967_295L
}
