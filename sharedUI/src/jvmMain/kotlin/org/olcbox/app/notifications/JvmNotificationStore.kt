package org.olcbox.app.notifications

import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class JvmNotificationStore(private val file:Path=DesktopPaths.appDataDir().resolve("device_notifications.json")):NotificationDeliveryStore {
    override fun load():NotificationDeliveryState {
        if(Files.isRegularFile(file))runCatching {return Json.decodeFromString<NotificationDeliveryState>(Files.readString(file))}
        return NotificationDeliveryState(UUID.randomUUID().toString().replace("-", "")).also(::save)
    }
    override fun save(value:NotificationDeliveryState) {DesktopPaths.writePrivateString(file,Json.encodeToString(NotificationDeliveryState.serializer(),value))}
}
