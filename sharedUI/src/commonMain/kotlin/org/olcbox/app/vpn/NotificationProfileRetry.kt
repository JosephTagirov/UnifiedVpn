package org.olcbox.app.vpn

internal fun notificationProfileRequestBusy(status: VpnStatus): Boolean =
    status is VpnStatus.Connecting || status is VpnStatus.Reconnecting || status is VpnStatus.Stopping

internal fun canRetryNotificationProfile(
    status: VpnStatus,
    foregroundStarted: Boolean,
    requestPending: Boolean,
    targetProfileId: String?,
    activeProfileId: String?,
    failedProfileId: String?,
    lifecycleStopping: Boolean = false
): Boolean {
    if (lifecycleStopping || !foregroundStarted || requestPending || notificationProfileRequestBusy(status) ||
        status is VpnStatus.Disconnected || targetProfileId.isNullOrBlank()
    ) return false
    return if (failedProfileId != null) targetProfileId == failedProfileId
    else status is VpnStatus.Error && targetProfileId == activeProfileId
}

// A previous Error/Connected value must never complete a newly submitted retry.
internal fun notificationProfileAttemptResult(
    targetProfileId: String,
    afterGeneration: Long,
    generation: Long,
    connectedGeneration: Long,
    status: VpnStatus,
    activeProfileId: String?,
    connectedProfileId: String?,
    failedProfileId: String?,
    failedGeneration: Long
): Boolean? {
    if (status is VpnStatus.Disconnected || status is VpnStatus.Stopping) return false
    if (failedProfileId == targetProfileId && failedGeneration > afterGeneration) return false
    if (generation <= afterGeneration) return null
    if (activeProfileId != targetProfileId) return false
    return when (status) {
        VpnStatus.Connected -> true.takeIf {
            connectedProfileId == targetProfileId && connectedGeneration > afterGeneration
        }
        is VpnStatus.Error -> false
        else -> null
    }
}
