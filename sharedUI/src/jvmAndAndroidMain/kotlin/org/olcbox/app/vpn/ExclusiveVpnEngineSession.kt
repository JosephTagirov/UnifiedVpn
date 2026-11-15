package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An AWG key remains owned while a suspended start is unwinding after stop or failure. */
internal class ExclusiveVpnEngineSession(
    private val identity: () -> String,
    private val owners: VpnEngineSessionOwners = VpnEngineSessionOwners.appAwgSessions
) {
    private val lock = Any()
    private val cleanupLock = Any()
    private var lease: VpnEngineSessionOwners.Lease? = null
    private var starting = false
    private var stopRequested = false

    suspend fun start(startEngine: suspend () -> Unit, stopEngine: () -> Unit) {
        val acquired = synchronized(lock) {
            if (lease != null) throw VpnPingUnavailableException("This AWG engine already owns a session")
            owners.acquire(identity()).also { lease = it; starting = true; stopRequested = false }
        }
        try {
            startEngine()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (stopRequested) throw CancellationException("AWG session stopped during startup")
                starting = false
            }
        } catch (failure: Exception) {
            synchronized(cleanupLock) {
                var cleaned = false
                try { stopEngine(); cleaned = true } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                synchronized(lock) {
                    starting = false
                    if (cleaned) { if (lease === acquired) lease = null } else stopRequested = false
                }
                if (cleaned) acquired.close()
            }
            throw failure
        }
    }

    fun stop(stopEngine: () -> Unit) {
        val held = synchronized(lock) {
            val current = lease ?: return
            if (stopRequested) return
            stopRequested = true
            current to starting
        }
        synchronized(cleanupLock) {
            synchronized(lock) { if (lease !== held.first) return }
            try { stopEngine() } catch (failure: Exception) {
                synchronized(lock) { if (lease === held.first) stopRequested = false }
                throw failure
            }
            // A pending start must clean up any process created after this stop before releasing ownership.
            if (!held.second) {
                synchronized(lock) { if (lease === held.first) lease = null }
                held.first.close()
            }
        }
    }
}

internal class VpnEngineSessionOwners {
    private val lock = Any()
    private val owners = mutableMapOf<String, Any>()

    fun acquire(identity: String): Lease = synchronized(lock) {
        require(identity.isNotEmpty()) { "An AWG session identity is required" }
        if (identity in owners) throw VpnPingUnavailableException("This AWG identity is already used by an active tunnel")
        val token = Any()
        owners[identity] = token
        Lease {
            synchronized(lock) { if (owners[identity] === token) owners.remove(identity) }
        }
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
        override fun toString() = "VpnEngineSessionLease(identity=[redacted])"
    }

    override fun toString() = "VpnEngineSessionOwners(identities=[redacted])"

    companion object { val appAwgSessions = VpnEngineSessionOwners() }
}
