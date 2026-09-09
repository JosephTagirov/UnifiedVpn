package org.olcbox.app.update

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import org.olcbox.app.desktop.WindowsProcessSecurity
import org.olcbox.app.desktop.WindowsTrustedExecutables
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.GZIPOutputStream

internal data class ValidatedPortableArchive(
    val archive: Path,
    val sha256: String,
    val expectedVersion: String,
    val archivePrefix: String
)

internal object WindowsPortableUpdater {
    private const val LAUNCHER_NAME = "UnifiedVPN.exe"
    private const val MAX_ARCHIVE_ENTRIES = 1_024
    private const val MAX_EXTRACTED_BYTES = 1_000_000_000L
    private const val MAX_CONFIG_BYTES = 1_048_576L
    private const val MAX_WINDOWS_COMMAND_CHARS = 30_000
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

    fun canReplaceInPlace(targetRoot: Path): Boolean {
        val target = targetRoot.toAbsolutePath().normalize()
        if (target.parent == null || target == target.root) return false
        if (requiredRelativePaths.any { !Files.isRegularFile(target.resolve(it)) }) return false
        if (listOf(
                target,
                target.resolve("app"),
                target.resolve("runtime"),
                target.resolve(LAUNCHER_NAME)
            ).any(::isReparsePoint)
        ) {
            return false
        }
        return canWriteDirectory(target)
    }

    fun validateArchive(
        archive: Path,
        expectedVersion: String,
        expectedSha256: String
    ): ValidatedPortableArchive {
        require(expectedVersion.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) {
            "Portable update version is invalid"
        }
        val expectedDigest = normalizeSha256(expectedSha256)
        val source = archive.toAbsolutePath().normalize()
        require(Files.isRegularFile(source) && Files.size(source) > 0L) {
            "Portable update archive is missing or empty"
        }
        require(sha256(source) == expectedDigest) {
            "Portable update SHA-256 mismatch"
        }

        var configText: String? = null
        val archivePrefix = ZipFile(source.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.isNotEmpty() && entries.size <= MAX_ARCHIVE_ENTRIES) {
                "Portable update contains an invalid number of files"
            }
            val normalizedEntries = entries.map { entry ->
                entry to normalizeEntryName(entry.name)
            }
            val collisionKeys = normalizedEntries.map { (_, name) -> name.lowercase(Locale.ROOT) }
            require(collisionKeys.distinct().size == collisionKeys.size) {
                "Portable update contains duplicate paths"
            }
            val fileNames = normalizedEntries
                .filterNot { (entry, _) -> entry.isDirectory }
                .map { (_, name) -> name }
            require(fileNames.none { fileName ->
                normalizedEntries.any { (_, otherName) ->
                    otherName != fileName && otherName.startsWith("$fileName/", ignoreCase = true)
                }
            }) {
                "Portable update contains a file-directory path collision"
            }

            val launcherEntries = normalizedEntries.filter { (entry, name) ->
                !entry.isDirectory && (name == LAUNCHER_NAME || name.endsWith("/$LAUNCHER_NAME"))
            }
            require(launcherEntries.size == 1) {
                "Portable update must contain exactly one $LAUNCHER_NAME"
            }
            val prefix = launcherEntries.single().second.removeSuffix(LAUNCHER_NAME)
            require(requiredRelativePaths.all { required ->
                normalizedEntries.any { (entry, name) ->
                    !entry.isDirectory && name.equals(prefix + required, ignoreCase = true)
                }
            }) {
                "Portable update is missing the embedded JVM or application files"
            }
            require(normalizedEntries.all { (_, name) ->
                prefix.isEmpty() ||
                    name.equals(prefix.removeSuffix("/"), ignoreCase = true) ||
                    name.startsWith(prefix, ignoreCase = true)
            }) {
                "Portable update contains files outside its application directory"
            }

            var extractedBytes = 0L
            normalizedEntries.forEach { (entry, normalizedName) ->
                if (entry.isDirectory) return@forEach
                val isConfig = normalizedName.equals(
                    prefix + "app/UnifiedVPN.cfg",
                    ignoreCase = true
                )
                val config = if (isConfig) java.io.ByteArrayOutputStream() else null
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        extractedBytes += read
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                            "Portable update is larger than the allowed limit"
                        }
                        config?.let {
                            require(it.size().toLong() + read <= MAX_CONFIG_BYTES) {
                                "Portable update configuration is too large"
                            }
                            it.write(buffer, 0, read)
                        }
                    }
                }
                if (config != null) {
                    configText = config.toString(StandardCharsets.UTF_8)
                }
            }
            prefix.removeSuffix("/")
        }

        require(configText.orEmpty().lineSequence().any { line ->
            line.trim() == "java-options=-Djpackage.app-version=$expectedVersion"
        }) {
            "Portable update version does not match $expectedVersion"
        }
        return ValidatedPortableArchive(
            archive = source,
            sha256 = expectedDigest,
            expectedVersion = expectedVersion,
            archivePrefix = archivePrefix
        )
    }

    fun launch(
        validatedArchive: ValidatedPortableArchive,
        targetRoot: Path,
        parentPid: Long
    ) {
        check(!WindowsProcessSecurity.shouldRefuseCurrentProcess()) {
            "Automatic replacement is disabled while Unified VPN runs as administrator. " +
                "Restart Unified VPN normally and check for updates again."
        }
        require(parentPid > 0L) { "Invalid updater parent process" }
        val target = targetRoot.toAbsolutePath().normalize()
        require(target.parent != null && target != target.root) { "Invalid update target directory" }
        require(Files.isRegularFile(validatedArchive.archive)) {
            "Validated portable update archive is missing"
        }
        requiredRelativePaths.forEach { relative ->
            require(Files.isRegularFile(target.resolve(relative))) {
                "Current Unified VPN installation is missing $relative"
            }
        }
        listOf(
            target,
            target.resolve("app"),
            target.resolve("runtime"),
            target.resolve(LAUNCHER_NAME)
        ).forEach { path ->
            check(!isReparsePoint(path)) {
                "Automatic replacement does not support reparse points in the installation"
            }
        }
        check(canReplaceInPlace(target)) {
            "Automatic replacement is unavailable for this system-wide installation. " +
                "Use the verified Unified VPN installer update instead."
        }

        val powershell = WindowsTrustedExecutables.powerShellPath().toString()
        val encodedCommand = encodedUpdaterCommand(
            validatedArchive = validatedArchive,
            targetRoot = target,
            parentPid = parentPid
        )
        check(encodedCommand.length < MAX_WINDOWS_COMMAND_CHARS) {
            "Bundled Windows updater command is too large to launch safely"
        }
        val updaterArguments = listOf(
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-EncodedCommand", encodedCommand
        )

        val process = ProcessBuilder(listOf(powershell) + updaterArguments)
            .redirectErrorStream(true)
            .start()
        check(!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
                .ifBlank { "Windows updater exited before Unified VPN closed" }
        }
    }

    internal fun updaterCommand(
        validatedArchive: ValidatedPortableArchive,
        targetRoot: Path,
        parentPid: Long
    ): String = buildString {
        appendLine("\$ParentPid = $parentPid")
        appendLine("\$ArchivePath = ${validatedArchive.archive.toString().powershellLiteral()}")
        appendLine("\$ExpectedSha256 = ${validatedArchive.sha256.powershellLiteral()}")
        appendLine("\$ExpectedVersion = ${validatedArchive.expectedVersion.powershellLiteral()}")
        appendLine("\$ArchivePrefix = ${validatedArchive.archivePrefix.powershellLiteral()}")
        appendLine("\$TargetRoot = ${targetRoot.toAbsolutePath().normalize().toString().powershellLiteral()}")
        appendLine("\$LauncherName = ${LAUNCHER_NAME.powershellLiteral()}")
        append(updaterScript())
    }

    internal fun encodedUpdaterCommand(
        validatedArchive: ValidatedPortableArchive,
        targetRoot: Path,
        parentPid: Long
    ): String {
        val compressed = java.io.ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(
                    updaterCommand(validatedArchive, targetRoot, parentPid)
                        .toByteArray(StandardCharsets.UTF_8)
                )
            }
            output.toByteArray()
        }
        val payload = Base64.getEncoder().encodeToString(compressed)
        val bootstrap = buildString {
            append("\$b=[Convert]::FromBase64String(")
            append(payload.powershellLiteral())
            append(");\$m=[IO.MemoryStream]::new(\$b);\$g=[IO.Compression.GZipStream]::new(")
            append("\$m,[IO.Compression.CompressionMode]::Decompress);\$r=[IO.StreamReader]::new(")
            append("\$g,[Text.Encoding]::UTF8);\$s=\$r.ReadToEnd();\$r.Dispose();")
            append("\$g.Dispose();\$m.Dispose();&([ScriptBlock]::Create(\$s))")
        }
        return Base64.getEncoder().encodeToString(
            bootstrap.toByteArray(StandardCharsets.UTF_16LE)
        )
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
        require(parts.none { part ->
            part.isBlank() ||
                part == "." ||
                part == ".." ||
                part.endsWith(' ') ||
                part.endsWith('.') ||
                INVALID_WINDOWS_PATH_CHAR.containsMatchIn(part) ||
                isReservedWindowsName(part)
        }) {
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

    private fun isReparsePoint(path: Path): Boolean {
        val attributes = systemDirectoryApi.GetFileAttributesW(WString(path.toString()))
        check(attributes != INVALID_FILE_ATTRIBUTES) {
            "Windows installation path attributes could not be read"
        }
        return attributes and FILE_ATTRIBUTE_REPARSE_POINT != 0
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
        return digest.digest().toHex()
    }

    private fun normalizeSha256(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        require(SHA256_PATTERN.matches(normalized)) {
            "Portable update requires a valid SHA-256 digest"
        }
        return normalized
    }

    private fun isReservedWindowsName(part: String): Boolean {
        val baseName = part.substringBefore('.').uppercase(Locale.ROOT)
        return baseName in RESERVED_WINDOWS_NAMES ||
            RESERVED_WINDOWS_DEVICE_PATTERN.matches(baseName)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    private val INVALID_WINDOWS_PATH_CHAR = Regex("[<>:\"|?*\\u0000-\\u001F]")
    private val RESERVED_WINDOWS_NAMES = setOf("CON", "PRN", "AUX", "NUL")
    private val RESERVED_WINDOWS_DEVICE_PATTERN = Regex("(?:COM|LPT)[1-9]")
    private const val INVALID_FILE_ATTRIBUTES = -1
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
    private val systemDirectoryApi: Kernel32SystemDirectory by lazy {
        Native.load("kernel32", Kernel32SystemDirectory::class.java)
    }

    private interface Kernel32SystemDirectory : StdCallLibrary {
        fun GetFileAttributesW(path: WString): Int
    }
}
