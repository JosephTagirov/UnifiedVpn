package org.olcbox.app.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path

class JvmAppearanceSettingsStore(
    private val file: Path = DesktopPaths.appDataDir().resolve("appearance_settings.json")
) : AppAppearanceSettingsStore {
    private val saveMutex = Mutex()

    override suspend fun load(): AppAppearanceSettings {
        return loadNow()
    }

    fun loadNow(): AppAppearanceSettings {
        return runCatching {
            if (!Files.exists(file)) return AppAppearanceSettings()
            json.decodeFromString(AppAppearanceSettings.serializer(), Files.readString(file))
        }.getOrDefault(AppAppearanceSettings())
    }

    override suspend fun save(settings: AppAppearanceSettings) {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                DesktopPaths.writePrivateString(
                    file,
                    json.encodeToString(AppAppearanceSettings.serializer(), settings)
                )
            }
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
