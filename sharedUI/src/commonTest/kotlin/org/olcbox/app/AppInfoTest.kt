package org.olcbox.app

import org.olcbox.app.data.model.OpenFluxSessionProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun exposesGeneratedOpenFluxProvenance() {
        assertEquals(GeneratedAppInfo.OPENFLUX_SHA, CurrentAppInfo.value.openFluxSha)
    }

    @Test
    fun diagnosticsIncludeEveryEngineWithoutChangingTheUserAgent() {
        val info = CurrentAppInfo.value
        val buildIdentity = "${info.name}/${info.version} build/${info.build}"

        assertEquals(buildIdentity, CurrentAppInfo.userAgent)
        assertEquals(
            "$buildIdentity olcrtc/${info.olcrtcSha.take(12)} " +
                "awg/${info.awgCoreSha.take(12)} xray/${info.xrayVersion}/${info.xraySha.take(12)} " +
                "openflux/${info.openFluxSha.take(12)} " +
                "openflux-session/${OpenFluxSessionProfileConfig.CORE_REVISION.take(12)} " +
                "vkcalls/5b1e6172a9f6",
            CurrentAppInfo.diagnosticVersion
        )
    }
}
