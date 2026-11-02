package org.olcbox.app.ui.localization

import org.olcbox.app.vpn.OpenFluxConnectionErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenFluxFailureLocalizationTest {
    @Test
    fun desktopStartupWrapperPreservesTheActionableRussianMessage() {
        val native = "OPENFLUX_FATAL: ${OpenFluxConnectionErrors.DOCUMENT_VIEWER}"
        val desktop = "OpenFlux failed before desktop proxy was enabled: $native"
        val translated = localizeUiText(OpenFluxConnectionErrors.DOCUMENT_VIEWER, "ru")
        assertTrue(translated.startsWith("Яндекс"))
        assertEquals(translated, localizeUiText(native, "ru"))
        assertEquals(translated, localizeUiText(desktop, "ru"))
        assertEquals(desktop, localizeUiText(desktop, "en"))
    }
}
