package org.olcbox.app.update

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.olcbox.app.desktop.WindowsProcessSecurity
import org.olcbox.app.desktop.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPortableUpdaterTest {
    @Test
    fun installedWindowsUpdatePrefersMatchingInstaller() {
        val portable = AppUpdateAsset(
            "UnifiedVPN-0.0.12-build.2026090401-windows-amd64-portable.zip",
            "https://example/portable.zip",
            100,
            digest = "sha256:${"a".repeat(64)}"
        )
        val installer = AppUpdateAsset(
            "UnifiedVPN-0.0.12-build.2026090401-windows-amd64-installer.exe",
            "https://example/installer.exe",
            100,
            digest = "sha256:${"b".repeat(64)}"
        )
        val info = AppUpdateInfo(
            channel = ReleaseChannel.Stable,
            version = "0.0.12",
            htmlUrl = "https://example/release",
            publishedAt = null,
            asset = portable,
            isUpdateAvailable = true,
            build = 2026090401L,
            windowsInstallerAsset = installer
        )

        assertEquals(
            installer,
            selectJvmUpdateAsset(
                info = info,
                os = DesktopOs.Windows,
                portableReplacementSupported = true
            )
        )
    }

    @Test
    fun portableZipIsOnlyFallbackWhenNoInstallerExists() {
        val portable = AppUpdateAsset(
            "UnifiedVPN-0.0.12-build.2026090401-windows-amd64-portable.zip",
            "https://example/portable.zip",
            100,
            digest = "sha256:${"a".repeat(64)}"
        )
        val info = AppUpdateInfo(
            channel = ReleaseChannel.Stable,
            version = "0.0.12",
            htmlUrl = "https://example/release",
            publishedAt = null,
            asset = portable,
            isUpdateAvailable = true,
            build = 2026090401L
        )

        assertEquals(
            portable,
            selectJvmUpdateAsset(info, DesktopOs.Windows, portableReplacementSupported = true)
        )
        assertFailsWith<IllegalStateException> {
            selectJvmUpdateAsset(info, DesktopOs.Windows, portableReplacementSupported = false)
        }
    }

    @Test
    fun readsNormalWindowsProcessAsNonElevated() {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) return

        assertFalse(
            WindowsProcessSecurity.shouldRefuseCurrentProcess(),
            "Gradle verification must run without an administrator token"
        )
    }

    @Test
    fun validatesCompletePortableArchiveWithoutExtractingIt() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("update.zip")
            writeArchive(archive, validEntries(version = "0.0.12"))

            val validated = validate(archive, expectedVersion = "0.0.12")

            assertEquals(archive.toAbsolutePath().normalize(), validated.archive)
            assertEquals("UnifiedVPN", validated.archivePrefix)
            assertEquals("0.0.12", validated.expectedVersion)
            assertFalse(Files.exists(root.resolve("staging")))
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
                archive,
                validEntries(version = "0.0.12") +
                    ("UnifiedVPN/../outside.txt" to "unsafe")
            )

            val error = assertFailsWith<IllegalArgumentException> {
                validate(archive, expectedVersion = "0.0.12")
            }

            assertTrue("unsafe path" in error.message.orEmpty())
            assertFalse(Files.exists(root.resolve("outside.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithWrongEmbeddedVersion() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("wrong-version.zip")
            writeArchive(archive, validEntries(version = "0.0.11"))

            val error = assertFailsWith<IllegalArgumentException> {
                validate(archive, expectedVersion = "0.0.12")
            }

            assertTrue("does not match" in error.message.orEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWhoseSha256Changed() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("changed.zip")
            writeArchive(archive, validEntries(version = "0.0.12"))

            val error = assertFailsWith<IllegalArgumentException> {
                WindowsPortableUpdater.validateArchive(
                    archive = archive,
                    expectedVersion = "0.0.12",
                    expectedSha256 = "0".repeat(64)
                )
            }

            assertTrue("SHA-256 mismatch" in error.message.orEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateArchivePaths() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            val archive = root.resolve("duplicate.zip")
            writeArchive(
                archive,
                validEntries(version = "0.0.12") +
                    ("UnifiedVPN/App/UnifiedVPN.cfg" to "duplicate")
            )

            val error = assertFailsWith<IllegalArgumentException> {
                validate(archive, expectedVersion = "0.0.12")
            }

            assertTrue("duplicate paths" in error.message.orEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsWindowsDeviceAndAlternateDataStreamPaths() {
        val root = createTempDirectory("unifiedvpn-update-test")
        try {
            listOf(
                "UnifiedVPN/CON/readme.txt",
                "UnifiedVPN/app/extra.txt:payload"
            ).forEachIndexed { index, unsafeName ->
                val archive = root.resolve("unsafe-$index.zip")
                writeArchive(
                    archive,
                    validEntries(version = "0.0.12") + (unsafeName to "unsafe")
                )

                val error = assertFailsWith<IllegalArgumentException> {
                    validate(archive, expectedVersion = "0.0.12")
                }
                assertTrue("unsafe path" in error.message.orEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun automaticJvmUpdateRequiresGithubSha256Digest() {
        assertEquals(
            "a".repeat(64),
            UpdateDownloadSecurity.normalizeGithubSha256Digest("sha256:${"A".repeat(64)}")
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.normalizeGithubSha256Digest(null)
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.normalizeGithubSha256Digest("sha1:${"a".repeat(40)}")
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.normalizeGithubSha256Digest("sha256:not-a-digest")
        }
    }

    @Test
    fun encodedUpdaterContainsImmutableCompressedScriptAndFitsWindowsLimit() {
        val root = createTempDirectory("unifiedvpn-update-command-test")
        try {
            val archive = root.resolve("update.zip")
            writeArchive(archive, validEntries(version = "0.0.12"))
            val validated = validate(archive, expectedVersion = "0.0.12")

            val command = WindowsPortableUpdater.updaterCommand(
                validatedArchive = validated,
                targetRoot = root.resolve("Installed Unified VPN"),
                parentPid = 1234L
            )
            assertTrue("\$ParentPid = 1234" in command)
            assertTrue("\$ExpectedSha256 = '${validated.sha256}'" in command)
            assertTrue("Installed Unified VPN" in command)
            assertFalse("apply-windows-update-" in command)

            val encoded = WindowsPortableUpdater.encodedUpdaterCommand(
                validatedArchive = validated,
                targetRoot = root.resolve("Installed Unified VPN"),
                parentPid = 1234L
            )
            val bootstrap = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE)
            assertTrue("GZipStream" in bootstrap)
            assertTrue("ScriptBlock" in bootstrap)
            assertTrue(encoded.length < 30_000, "Encoded command has ${encoded.length} characters")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun updaterCommitsBeforeBestEffortBackupCleanup() {
        val script = WindowsPortableUpdater.updaterScript()
        val hashCheck = script.indexOf("Get-Sha256 \$trustedArchive")
        val restart = script.indexOf("Start-ExplorerLauncher \$launcher")
        val commit = script.indexOf("\$committed = \$true")
        val backupCleanup = script.indexOf("Remove-UpdateDirectory -Path \$backup")
        val rollback = script.indexOf("Restore-PreviousVersion -Target \$target")

        assertTrue(hashCheck >= 0)
        assertTrue(restart > hashCheck)
        assertTrue(commit > restart)
        assertTrue(backupCleanup > commit)
        assertTrue(rollback > backupCleanup)
        assertTrue("\$PSModuleAutoLoadingPreference = 'None'" in script)
        assertTrue("Test-CurrentProcessElevated" in script)
        assertTrue("disabled for an administrator process" in script)
        assertFalse("Expand-Archive" in script)
        assertFalse("Get-FileHash" in script)
        assertFalse("Where-Object" in script)
        assertFalse("-Verb RunAs" in script)
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

    private fun validate(archive: Path, expectedVersion: String): ValidatedPortableArchive =
        WindowsPortableUpdater.validateArchive(
            archive = archive,
            expectedVersion = expectedVersion,
            expectedSha256 = sha256(archive)
        )

    private fun validEntries(version: String): List<Pair<String, String>> = listOf(
        "UnifiedVPN/UnifiedVPN.exe" to "launcher",
        "UnifiedVPN/app/UnifiedVPN.cfg" to
            "[JavaOptions]\njava-options=-Djpackage.app-version=$version\n",
        "UnifiedVPN/runtime/bin/server/jvm.dll" to "jvm",
        "UnifiedVPN/runtime/lib/modules" to "modules"
    )

    private fun writeArchive(archive: Path, entries: List<Pair<String, String>>) {
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
