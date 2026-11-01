package org.olcbox.app.ui.components
import java.io.File
actual fun ownerCabinetEnabled(): Boolean = System.getenv("APPDATA")?.let {
    File(it,"Olcbox/owner-cabinet.enabled").isFile
} ?: false
