package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationEntry

internal object AutoProfileSpeed {
    data class Measurement(val currentBitsPerSecond: Long, val candidateBitsPerSecond: Long)

    fun nextCandidate(current: LocationEntry, profiles: List<LocationEntry>, afterId: String?): LocationEntry? {
        val candidates = profiles.filter { isCandidate(current, it) }
        val previousIndex = candidates.indexOfFirst { it.storageId == afterId }
        return candidates.getOrNull(previousIndex + 1) ?: candidates.firstOrNull()
    }

    private fun isCandidate(current: LocationEntry, candidate: LocationEntry): Boolean {
        if (candidate.storageId == current.storageId || !candidate.isComplete()) return false
        val profile = candidate.profile
        if (profile.localSocksPort != null || profile.isOpenFlux()) return false
        if (!profile.isOlcRtc()) return true
        val location = candidate.location
        if (!location.usesModernRtcEngine()) return false
        if (!current.profile.isOlcRtc()) return true
        val currentLocation = current.location
        return !currentLocation.key.equals(location.key, ignoreCase = true) &&
            (currentLocation.bypassProvider != location.bypassProvider || currentLocation.id != location.id)
    }

    fun isFaster(currentBitsPerSecond: Long?, candidateBitsPerSecond: Long?): Boolean =
        currentBitsPerSecond != null && currentBitsPerSecond > 0 &&
            candidateBitsPerSecond != null && candidateBitsPerSecond >= currentBitsPerSecond * 1.25

    suspend fun confirmImprovement(
        measureCurrent: suspend () -> Long?,
        measureCandidate: suspend () -> Long?,
        sameConnection: suspend () -> Boolean
    ): Measurement? {
        suspend fun compare(): Measurement? {
            if (!sameConnection()) return null
            val current = measureCurrent() ?: return null
            val candidate = measureCandidate() ?: return null
            return Measurement(current, candidate).takeIf {
                sameConnection() && isFaster(current, candidate)
            }
        }

        compare() ?: return null
        return compare()
    }
}
