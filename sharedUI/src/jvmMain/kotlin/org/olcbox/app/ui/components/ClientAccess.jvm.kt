package org.olcbox.app.ui.components

import org.olcbox.app.desktop.DesktopPaths
import java.io.File

actual fun ownerCabinetEnabled(): Boolean = resolveOwnerCabinetEnabled(
    ownerEdition = java.lang.Boolean.getBoolean("unifiedvpn.ownerCabinet"),
    marker = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        ?.let { File(it, "Olcbox/owner-cabinet.enabled") }
)

/** Preserve the existing per-user owner edition when a later update replaces the launcher. */
internal fun resolveOwnerCabinetEnabled(ownerEdition: Boolean, marker: File?): Boolean {
    if (ownerEdition && marker != null && !marker.isFile) {
        runCatching { DesktopPaths.writePrivateString(marker.toPath(), "owner-edition\n") }
    }
    return ownerEdition || marker?.isFile == true
}
