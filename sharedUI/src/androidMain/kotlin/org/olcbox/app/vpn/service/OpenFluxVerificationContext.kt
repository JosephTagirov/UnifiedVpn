package org.olcbox.app.vpn.service

import android.app.PendingIntent

internal class OpenFluxVerificationContext(
    val profileStorageId: String,
    val generation: Long,
    val isCurrent: () -> Boolean,
    val onPrompt: (OpenFluxVerificationPrompt?) -> Unit
) {
    init {
        require(profileStorageId.isNotBlank() && profileStorageId.length <= 256)
        require(generation >= 0)
    }
}

internal class OpenFluxVerificationPrompt(
    val nonce: String,
    val profileStorageId: String,
    val generation: Long,
    val pendingIntent: PendingIntent
) {
    override fun toString(): String = "OpenFluxVerificationPrompt([redacted])"
}

/** One connection attempt owns this lease; it is never written to profile storage. */
internal class OpenFluxBrowserLease(
    private var session: OpenFluxBrowserSession?,
    private val documentUrl: String,
    private val isCurrent: () -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val createdAt = nowMillis()

    @Synchronized
    fun requireCurrent() {
        val age = nowMillis() - createdAt
        if (session == null || age !in 0L until OPENFLUX_BROWSER_LEASE_MS || !runCatching(isCurrent).getOrDefault(false)) {
            session = null
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Expired)
        }
    }

    @Synchronized
    fun consume(requestedDocumentUrl: String): OpenFluxBrowserSession {
        requireCurrent()
        val value = checkNotNull(session)
        session = null
        if (requestedDocumentUrl != documentUrl) {
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
        }
        value.classicDocument?.requireCurrent(documentUrl)
        return value
    }

    @Synchronized
    fun invalidate() {
        session = null
    }

    override fun toString(): String = "OpenFluxBrowserLease([redacted])"
}

internal const val OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS = 120_000L
internal const val OPENFLUX_BROWSER_LEASE_MS = 120_000L
