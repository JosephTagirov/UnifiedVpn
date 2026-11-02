package org.olcbox.app.ui.components

import java.nio.file.Files
import kotlin.test.*

class OwnerEditionTest {
    @Test fun ordinaryEditionDoesNotEnableOrCreateTheOwnerMarker() {
        val root = Files.createTempDirectory("owner-edition-test-")
        val marker = root.resolve("Olcbox/owner-cabinet.enabled").toFile()
        assertFalse(resolveOwnerCabinetEnabled(false, marker))
        assertFalse(marker.parentFile.exists())
    }

    @Test fun ownerEditionIsRetainedAfterAnOrdinaryUpdate() {
        val root = Files.createTempDirectory("owner-edition-test-")
        val marker = root.resolve("Olcbox/owner-cabinet.enabled").toFile()
        assertTrue(resolveOwnerCabinetEnabled(true, marker))
        assertTrue(marker.isFile)
        assertTrue(resolveOwnerCabinetEnabled(false, marker))
    }

    @Test fun previousOwnerMarkersRemainCompatible() {
        val marker = Files.createTempFile("owner-cabinet-", ".enabled").toFile()
        assertTrue(resolveOwnerCabinetEnabled(false, marker))
    }

    @Test fun ownerBuildWorksWhenAppDataIsUnavailable() {
        assertTrue(resolveOwnerCabinetEnabled(true, null))
        assertFalse(resolveOwnerCabinetEnabled(false, null))
    }
}
