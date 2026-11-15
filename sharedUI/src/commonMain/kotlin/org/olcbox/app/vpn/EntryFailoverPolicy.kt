package org.olcbox.app.vpn

internal enum class VpnEntryChoice { PRIMARY, BACKUP }

/** A failed readback makes the actual override unknown even when the preceding write succeeded. */
internal class EntryRouteAcknowledgement {
    var entry: VpnEntryChoice? = null
        private set
    var blocked: Boolean = true
        private set
    fun selected(choice: VpnEntryChoice, confirmed: Boolean) {
        entry = choice.takeIf { confirmed }
        blocked = false
    }
    fun blocked(confirmed: Boolean) {
        entry = null
        blocked = confirmed
    }
    fun needsSelection(choice: VpnEntryChoice) = entry != choice
    fun needsBlock() = entry != null || !blocked
}

/** Decisions use completed data-path probes; applying a decision is a separate operation. */
internal class EntryFailoverPolicy(
    nowMillis: Long,
    private val failuresBeforeSwitch: Int = 2,
    private val successesBeforeReturn: Int = 3,
    private val minimumBackupMillis: Long = 30_000
) {
    var selected: VpnEntryChoice = VpnEntryChoice.PRIMARY
        private set
    private var selectedAt = nowMillis
    private var primaryFailures = 0
    private var primarySuccesses = 0
    private var hasAcknowledgedRoute = false

    fun observe(primaryHealthy: Boolean, backupHealthy: Boolean, nowMillis: Long): VpnEntryChoice? {
        primaryFailures = if (primaryHealthy) 0 else (primaryFailures + 1).coerceAtMost(failuresBeforeSwitch)
        primarySuccesses = if (primaryHealthy) (primarySuccesses + 1).coerceAtMost(successesBeforeReturn) else 0
        // During startup there is no working route to preserve. Start with a proven entry;
        // hysteresis applies after its selection has been acknowledged by the native core.
        if (!hasAcknowledgedRoute) return when {
            primaryHealthy -> VpnEntryChoice.PRIMARY
            backupHealthy -> VpnEntryChoice.BACKUP
            else -> null
        }
        return when (selected) {
            VpnEntryChoice.PRIMARY -> VpnEntryChoice.BACKUP.takeIf {
                primaryFailures >= failuresBeforeSwitch && backupHealthy
            }
            VpnEntryChoice.BACKUP -> VpnEntryChoice.PRIMARY.takeIf {
                primarySuccesses >= successesBeforeReturn && nowMillis - selectedAt >= minimumBackupMillis
            }
        }
    }

    /** Called only after the routing API accepted the new selection. */
    fun switchedTo(choice: VpnEntryChoice, nowMillis: Long) {
        val firstAcknowledgement = !hasAcknowledgedRoute
        hasAcknowledgedRoute = true
        if (choice == selected && !firstAcknowledgement) return
        selected = choice
        selectedAt = nowMillis
        primaryFailures = 0
        primarySuccesses = 0
    }
}
