package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String,
    val build: Long,
    val sourceAttribution: String,
    val olcrtcSha: String,
    val awgCoreSha: String,
    val xrayVersion: String,
    val xraySha: String
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = GeneratedAppInfo.NAME,
        version = GeneratedAppInfo.VERSION,
        build = GeneratedAppInfo.BUILD,
        sourceAttribution = GeneratedAppInfo.SOURCE_ATTRIBUTION,
        olcrtcSha = GeneratedAppInfo.OLCRTC_SHA,
        awgCoreSha = GeneratedAppInfo.AWG_CORE_SHA,
        xrayVersion = GeneratedAppInfo.XRAY_VERSION,
        xraySha = GeneratedAppInfo.XRAY_SHA
    )

    val userAgent: String = "${value.name}/${value.version} build/${value.build}"
    val diagnosticVersion: String =
        "${value.name}/${value.version} build/${value.build} olcrtc/${value.olcrtcSha.take(12)} " +
            "awg/${value.awgCoreSha.take(12)} xray/${value.xrayVersion}/${value.xraySha.take(12)}"
}
