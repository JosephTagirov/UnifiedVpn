package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String,
    val sourceAttribution: String,
    val olcrtcSha: String,
    val awgCoreSha: String
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = GeneratedAppInfo.NAME,
        version = GeneratedAppInfo.VERSION,
        sourceAttribution = GeneratedAppInfo.SOURCE_ATTRIBUTION,
        olcrtcSha = GeneratedAppInfo.OLCRTC_SHA,
        awgCoreSha = GeneratedAppInfo.AWG_CORE_SHA
    )

    val userAgent: String = "${value.name}/${value.version}"
    val diagnosticVersion: String =
        "${value.name}/${value.version} olcrtc/${value.olcrtcSha.take(12)} " +
            "awg/${value.awgCoreSha.take(12)}"
}
