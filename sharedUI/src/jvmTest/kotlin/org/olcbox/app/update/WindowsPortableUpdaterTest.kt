package org.olcbox.app.update

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsPortableUpdaterTest {
    @Test
    fun stagesCompletePortableArchive() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("update.zip")
            writeArchive(
                archive = archive,
                entries = validEntries(version = "0.0.8")
            )

            val staged = WindowsPortableUpdater.stage(
                archive = archive,
                stagingParent = root.resolve("staging"),
                expectedVersion = "0.0.8"
            )

            assertTrue(Files.isRegularFile(staged.resolve("UnifiedVPN.exe")))
            assertTrue(Files.isRegularFile(staged.resolve("runtime/lib/modules")))
            assertTrue("jpackage.app-version=0.0.8" in staged.resolve("app/UnifiedVPN.cfg").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsArchivePathTraversal() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("unsafe.zip")
            writeArchive(
                archive = archive,
                entries = validEntries(version = "0.0.8") +
                    ("UnifiedVPN/../outside.txt" to "unsafe")
            )

            val error = assertFailsWith<IllegalArgumentException> {
                WindowsPortableUpdater.stage(
                    archive = archive,
                    stagingParent = root.resolve("staging"),
                    expectedVersion = "0.0.8"
                )
            }

            assertTrue("unsafe path" in error.message.orEmpty())
            assertEquals(false, Files.exists(root.resolve("outside.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithWrongEmbeddedVersion() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("wrong-version.zip")
            writeArchive(archive, validEntries(version = "0.0.7"))

            val error = assertFailsWith<IllegalArgumentException> {
                WindowsPortableUpdater.stage(
                    archive = archive,
                    stagingParent = root.resolve("staging"),
                    expectedVersion = "0.0.8"
                )
            }

            assertTrue("does not match" in error.message.orEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun generatedPowerShellUpdaterHasValidSyntax() {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) return
        val root = createTempDirectory("unifiedvpn-update-script-test")
        try {
            val script = root.resolve("update.ps1")
            Files.writeString(script, WindowsPortableUpdater.updaterScript())
            val parserCommand = """
                ${'$'}tokens = ${'$'}null
                ${'$'}errors = ${'$'}null
                [void][System.Management.Automation.Language.Parser]::ParseFile(
                    '${script.toString().replace("'", "''")}',
                    [ref]${'$'}tokens,
                    [ref]${'$'}errors
                )
                if (${'$'}errors.Count -gt 0) {
                    ${'$'}errors | ForEach-Object { Write-Error ${'$'}_.Message }
                    exit 1
                }
            """.trimIndent()
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                parserCommand
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor(), output)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun validEntries(version: String): List<Pair<String, String>> = listOf(
        "UnifiedVPN/UnifiedVPN.exe" to "launcher",
        "UnifiedVPN/app/UnifiedVPN.cfg" to
            "[JavaOptions]\njava-options=-Djpackage.app-version=$version\n",
        "UnifiedVPN/runtime/bin/server/jvm.dll" to "jvm",
        "UnifiedVPN/runtime/lib/modules" to "modules"
    )

    private fun writeArchive(
        archive: java.nio.file.Path,
        entries: List<Pair<String, String>>
    ) {
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
