package org.olcbox.app.ui.components
import org.olcbox.app.data.model.SubscriptionMetadata
import kotlin.time.Clock
import kotlin.time.Instant

// A missing term is unknown, never implicitly unlimited.
internal fun SubscriptionMetadata.accessTerm(nowSeconds: Long = Clock.System.now().epochSeconds): String? {
    val end = accessExpires ?: return null
    if (end == 0L) return "Бессрочно"
    if (end <= nowSeconds) return "Срок истёк"
    val date = runCatching { Instant.fromEpochSeconds(end).toString().take(10) }.getOrNull() ?: return null
    return "до " + date.substring(8,10) + "." + date.substring(5,7) + "." + date.substring(2,4)
}
internal fun SubscriptionMetadata.safePortalUrl(): String? = clientPortalUrl?.takeIf {
    Regex("https://panel\\.meesharr\\.space/vpn-control/client/[A-Za-z0-9_-]{43}").matches(it)
}
expect fun ownerCabinetEnabled(): Boolean
