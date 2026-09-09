package org.olcbox.app.desktop

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPathsTest {
    @Test
    fun privateWriterReplacesContentAndRestrictsPosixPermissions() {
        val root = createTempDirectory("unifiedvpn-private-files")
        try {
            val target = root.resolve("nested/profile.json")
            DesktopPaths.writePrivateString(target, "first")
            DesktopPaths.writePrivateString(target, "second")

            assertEquals("second", Files.readString(target))
            if (DesktopPaths.os != DesktopOs.Windows) {
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(target.parent)
                )
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(target)
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun privateTemporaryWriterCreatesNonemptyFile() {
        val root = createTempDirectory("unifiedvpn-private-temp")
        try {
            val target = DesktopPaths.writePrivateTempString(root, "engine-", ".json", "secret")
            assertEquals("secret", Files.readString(target))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
