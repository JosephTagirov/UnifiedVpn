package org.olcbox.app.data.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogSelection(val lastLines: Int=100,val problemsOnly: Boolean=false)
object LogPresentation {
    private val pattern0 = Regex("""(?i)\b(error|fatal|failed|failure|exception)\b|❌|не удалось""")
    private val pattern1 = Regex("""(?i)\b(warn|warning|retry|reconnecting|timeout)\b|⚠""")
    private val pattern2 = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}]""")
    private val pattern3 = Regex("""(?i)vk[ -]?calls""")
    private val pattern4 = Regex("""(?i)vless|xray""")
    private val pattern5 = Regex("""(?i)amnezia|wireguard|\bawg\b""")

    private val _selection=MutableStateFlow(LogSelection())
    val selection=_selection.asStateFlow()
    fun select(value: LogSelection) { _selection.value=value.copy(lastLines=value.lastLines.coerceIn(0,10000)) }
    fun lines(logs: List<String>, options: LogSelection=_selection.value): List<String> {
        val filtered=if(options.problemsOnly) logs.filter { severity(it) in setOf("ERROR","WARN") } else logs
        return (if(options.lastLines==0) filtered else filtered.takeLast(options.lastLines)).map(::sanitizeDiagnosticLogLine)
    }
    fun export(logs: List<String>): String {
        val selected=lines(logs)
        return buildString {
            appendLine("Unified VPN application logs")
            appendLine("Build: "+org.olcbox.app.CurrentAppInfo.diagnosticVersion)
            appendLine("Entries: "+selected.size+" / "+logs.size)
            appendLine("Format: local time | level | protocol | message")
            appendLine()
            selected.forEach(::appendLine)
        }
    }
    private val declaredLevel = Regex("""(?i)(?:^|[\s\[:])(ERROR|FATAL|WARN|WARNING|INFO|DEBUG)(?=[:\]\[])""")
    fun severity(message: String): String {
        val level=declaredLevel.find(message)?.groupValues?.get(1)?.uppercase()
        if(level!=null) return when(level) { "FATAL"->"ERROR";"WARNING"->"WARN";else->level }
        return when {
        pattern0.containsMatchIn(message)->"ERROR"
        pattern1.containsMatchIn(message)->"WARN"
        else->"INFO"
        }
    }
    fun entry(message: String, time: String=diagnosticLogTime()): String {
        val clean=sanitizeDiagnosticLogLine(message)
        if(pattern2.containsMatchIn(clean)) return clean
        val source=when {
            pattern3.containsMatchIn(clean)->"VK Calls"
            clean.contains("openflux",true)->"OpenFlux"
            pattern4.containsMatchIn(clean)->"VLESS"
            pattern5.containsMatchIn(clean)->"AWG"
            clean.startsWith("rtc:") || clean.contains("olcrtc",true)->"RTC"
            else->"APP"
        }
        return "[$time] [${severity(clean)}] [$source] $clean"
    }
}
internal expect fun diagnosticLogTime(): String
