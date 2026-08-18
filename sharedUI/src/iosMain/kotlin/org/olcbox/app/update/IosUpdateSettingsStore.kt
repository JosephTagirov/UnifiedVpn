package org.olcbox.app.update

import platform.Foundation.NSUserDefaults

class IosUpdateSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : AppUpdateSettingsStore {
    override suspend fun load(): AppUpdateSettings {
        val channel = defaults.stringForKey(KEY_CHANNEL)
            ?.let { value -> runCatching { ReleaseChannel.valueOf(value) }.getOrNull() }
            ?: ReleaseChannel.Stable

        val lastCheck = defaults.integerForKey(KEY_LAST_CHECK)
            .takeIf { it > 0L }

        return AppUpdateSettings(
            channel = channel,
            intervalHours = defaults.integerForKey(KEY_INTERVAL_HOURS)
                .toInt()
                .takeIf { it > 0 }
                ?: AppUpdateSettings.DEFAULT_INTERVAL_HOURS,
            lastCheckAtEpochMs = lastCheck,
            lastSeenUpdateVersion = defaults.stringForKey(KEY_LAST_SEEN),
            lastDownloadedUpdateVersion = defaults.stringForKey(KEY_LAST_DOWNLOADED),
            lastSeenOlcboxRelease = defaults.stringForKey(KEY_LAST_SEEN_OLCBOX),
            lastSeenAmneziaRelease = defaults.stringForKey(KEY_LAST_SEEN_AMNEZIA)
        ).normalized()
    }

    override suspend fun save(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        defaults.setObject(normalized.channel.name, KEY_CHANNEL)
        defaults.setInteger(normalized.intervalHours.toLong(), KEY_INTERVAL_HOURS)
        defaults.setInteger(normalized.lastCheckAtEpochMs ?: 0L, KEY_LAST_CHECK)
        defaults.setOptionalString(normalized.lastSeenUpdateVersion, KEY_LAST_SEEN)
        defaults.setOptionalString(normalized.lastDownloadedUpdateVersion, KEY_LAST_DOWNLOADED)
        defaults.setOptionalString(normalized.lastSeenOlcboxRelease, KEY_LAST_SEEN_OLCBOX)
        defaults.setOptionalString(normalized.lastSeenAmneziaRelease, KEY_LAST_SEEN_AMNEZIA)
    }

    private fun NSUserDefaults.setOptionalString(value: String?, key: String) {
        if (value == null) {
            removeObjectForKey(key)
        } else {
            setObject(value, key)
        }
    }

    private companion object {
        const val KEY_CHANNEL = "update_channel"
        const val KEY_INTERVAL_HOURS = "update_interval_hours"
        const val KEY_LAST_CHECK = "last_update_check_at_epoch_ms"
        const val KEY_LAST_SEEN = "last_seen_update_version"
        const val KEY_LAST_DOWNLOADED = "last_downloaded_update_version"
        const val KEY_LAST_SEEN_OLCBOX = "last_seen_olcbox_release"
        const val KEY_LAST_SEEN_AMNEZIA = "last_seen_amnezia_release"
    }
}
