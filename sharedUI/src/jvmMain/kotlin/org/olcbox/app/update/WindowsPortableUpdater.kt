package org.olcbox.app.update

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal object WindowsPortableUpdater {
    private const val LAUNCHER_NAME = "UnifiedVPN.exe"
    private const val MAX_ARCHIVE_ENTRIES = 1_024
    private const val MAX_EXTRACTED_BYTES = 1_000_000_000L
    private val requiredRelativePaths = listOf(
        LAUNCHER_NAME,
        "app/UnifiedVPN.cfg",
        "runtime/bin/server/jvm.dll",
        "runtime/lib/modules"
    )

    fun currentAppRoot(): Path? {
        val candidates = buildList {
            System.getProperty("jpackage.app-path")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(::add)
            ProcessHandle.current().info().command().orElse(null)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(::add)
            System.getProperty("user.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.resolve(LAUNCHER_NAME)
                ?.let(::add)
        }

        return candidates.asSequence()
            .map { it.toAbsolutePath().normalize() }
            .map { if (Files.isDirectory(it)) it else it.parent }
            .filterNotNull()
            .distinct()
            .firstOrNull { root ->
                Files.isRegularFile(root.resolve(LAUNCHER_NAME)) &&
                    Files.isRegularFile(root.resolve("app/UnifiedVPN.cfg"))
            }
    }

    fun stage(
        archive: Path,
        stagingParent: Path,
        expectedVersion: String
    ): Path {
        require(Files.isRegularFile(archive) && Files.size(archive) > 0L) {
            "Portable update archive is missing or empty"
        }
        val parent = stagingParent.toAbsolutePath().normalize()
        Files.createDirectories(parent)
        val stagingRoot = parent.resolve(
            "unifiedvpn-${expectedVersion.fileToken()}-${UUID.randomUUID()}"
        ).normalize()
        require(stagingRoot.parent == parent) { "Invalid update staging path" }
        Files.createDirectories(stagingRoot)

        try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= MAX_ARCHIVE_ENTRIES) {
                    "Portable update contains too many files"
                }
                val normalizedNames = entries.map { entry -> normalizeEntryName(entry.name) }
                val launcherEntries = normalizedNames.filter { name ->
                    name == LAUNCHER_NAME || name.endsWith("/$LAUNCHER_NAME")
                }
                require(launcherEntries.size == 1) {
                    "Portable update must contain exactly one $LAUNCHER_NAME"
                }
                val archivePrefix = launcherEntries.single().removeSuffix(LAUNCHER_NAME)
                require(requiredRelativePaths.all { required ->
                    normalizedNames.contains(archivePrefix + required)
                }) {
                    "Portable update is missing the embedded JVM or application files"
                }
                require(normalizedNames.all { name ->
                    name == archivePrefix.removeSuffix("/") || name.startsWith(archivePrefix)
                }) {
                    "Portable update contains files outside its application directory"
                }

                var extractedBytes = 0L
                entries.zip(normalizedNames).forEach { (entry, normalizedName) ->
                    val relativeName = normalizedName.removePrefix(archivePrefix)
                    if (relativeName.isBlank()) return@forEach
                    val target = stagingRoot.resolve(relativeName).normalize()
                    require(target.startsWith(stagingRoot)) {
                        "Portable update contains an unsafe path"
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(target).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    extractedBytes += read
                                    require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                        "Portable update is larger than the allowed limit"
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            }

            requiredRelativePaths.forEach { relative ->
                val file = stagingRoot.resolve(relative)
                require(Files.isRegularFile(file) && Files.size(file) > 0L) {
                    "Portable update did not extract $relative"
                }
            }
            val config = Files.readString(stagingRoot.resolve("app/UnifiedVPN.cfg"))
            require("java-options=-Djpackage.app-version=$expectedVersion" in config) {
                "Portable update version does not match $expectedVersion"
            }
            return stagingRoot
        } catch (error: Throwable) {
            deleteTree(stagingRoot, parent)
            throw error
        }
    }

    fun launch(
        stagedRoot: Path,
        targetRoot: Path,
        workingDirectory: Path,
        parentPid: Long
    ) {
        val staged = stagedRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(staged != target && target.parent != null) { "Invalid update target directory" }
        requiredRelativePaths.forEach { relative ->
            require(Files.isRegularFile(staged.resolve(relative))) {
                "Staged update is missing $relative"
            }
        }
        require(Files.isRegularFile(target.resolve(LAUNCHER_NAME))) {
            "Current Unified VPN launcher was not found"
        }

        Files.createDirectories(workingDirectory)
        val script = workingDirectory.resolve("apply-windows-update-${UUID.randomUUID()}.ps1")
        val log = workingDirectory.resolve("windows-update.log")
        Files.writeString(script, updaterScript(), StandardCharsets.UTF_8)
        val updaterArguments = listOf(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", script.toString(),
            "-ParentPid", parentPid.toString(),
            "-StagedRoot", staged.toString(),
            "-TargetRoot", target.toString(),
            "-LauncherName", LAUNCHER_NAME,
            "-LogFile", log.toString()
        )

        if (canWriteDirectory(target)) {
            ProcessBuilder(listOf("powershell.exe") + updaterArguments)
                .directory(workingDirectory.toFile())
                .start()
            return
        }

        val elevatedArgumentLine = updaterArguments.joinToString(" ", transform = ::windowsQuote)
        val elevationCommand = buildString {
            append("\$ErrorActionPreference = 'Stop'; ")
            append("\$process = Start-Process -FilePath 'powershell.exe' ")
            append("-ArgumentList ")
            append(elevatedArgumentLine.powershellLiteral())
            append(" -Verb RunAs -WindowStyle Hidden -PassThru; ")
            append("if (\$null -eq \$process) { throw 'Updater elevation was cancelled' }")
        }
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-Command", elevationCommand
        )
            .redirectErrorStream(true)
            .start()
        check(process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out waiting for updater permission"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) {
            output.ifBlank { "Windows updater permission was denied" }
        }
    }

    internal fun updaterScript(): String =
        checkNotNull(javaClass.getResourceAsStream("/windows/apply-update.ps1")) {
            "Bundled Windows updater script is missing"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun normalizeEntryName(rawName: String): String {
        require(rawName.isNotBlank() && '\u0000' !in rawName) {
            "Portable update contains an invalid path"
        }
        val name = rawName.replace('\\', '/').removeSuffix("/")
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "Portable update contains an absolute path"
        }
        val parts = name.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Portable update contains an unsafe path"
        }
        return name
    }

    private fun canWriteDirectory(directory: Path): Boolean {
        val probe = directory.resolve(".unifiedvpn-update-${UUID.randomUUID()}.tmp")
        return runCatching {
            Files.writeString(probe, "probe", StandardCharsets.US_ASCII)
            Files.delete(probe)
            true
        }.getOrElse {
            runCatching { Files.deleteIfExists(probe) }
            false
        }
    }

    private fun deleteTree(target: Path, allowedParent: Path) {
        val normalized = target.toAbsolutePath().normalize()
        require(normalized.parent == allowedParent.toAbsolutePath().normalize()) {
            "Refusing to delete an unsafe staging directory"
        }
        if (!Files.exists(normalized)) return
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun String.fileToken(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "unknown" }

    private fun windowsQuote(value: String): String =
        "\"${value.replace("\"", "\\\"")}\""

    private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"
}
