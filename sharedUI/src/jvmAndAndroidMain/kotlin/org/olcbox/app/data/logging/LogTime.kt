package org.olcbox.app.data.logging
import java.time.LocalTime
import java.time.format.DateTimeFormatter
internal actual fun diagnosticLogTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
