package org.olcbox.app.desktop

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path

internal enum class DesktopOs {
    Windows,
    Linux,
    Other
}

internal object DesktopPaths {
    val os: DesktopOs
        get() {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.contains("windows") -> DesktopOs.Windows
                name.contains("linux") -> DesktopOs.Linux
                else -> DesktopOs.Other
            }
        }

    val arch: String
        get() = System.getProperty("os.arch").lowercase()

    fun appDataDir(): Path {
        val home = Path(System.getProperty("user.home"))
        val dir = when (os) {
            DesktopOs.Windows -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let { Path(it) } ?: home.resolve("AppData").resolve("Roaming")).resolve("Olcbox")
            }
            DesktopOs.Linux,
            DesktopOs.Other -> home.resolve(".olcbox")
        }
        return ensurePrivateDirectory(dir)
    }

    fun ensurePrivateDirectory(directory: Path): Path {
        Files.createDirectories(directory)
        applyPrivatePermissions(directory, isDirectory = true)
        return directory
    }

    fun writePrivateString(
        target: Path,
        content: String,
        charset: Charset = StandardCharsets.UTF_8
    ): Path {
        val absoluteTarget = target.toAbsolutePath().normalize()
        val parent = absoluteTarget.parent
            ?: error("Private file must have a parent directory: $target")
        ensurePrivateDirectory(parent)

        val temporary = Files.createTempFile(parent, ".${absoluteTarget.fileName}.", ".tmp")
        try {
            applyPrivatePermissions(temporary, isDirectory = false)
            Files.writeString(
                temporary,
                content,
                charset,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            applyPrivatePermissions(temporary, isDirectory = false)
            try {
                Files.move(
                    temporary,
                    absoluteTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING)
            }
            applyPrivatePermissions(absoluteTarget, isDirectory = false)
            return absoluteTarget
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun writePrivateTempString(
        directory: Path,
        prefix: String,
        suffix: String,
        content: String
    ): Path {
        val privateDirectory = ensurePrivateDirectory(directory)
        val target = Files.createTempFile(privateDirectory, prefix, suffix)
        return try {
            applyPrivatePermissions(target, isDirectory = false)
            Files.writeString(
                target,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            applyPrivatePermissions(target, isDirectory = false)
            target
        } catch (error: Throwable) {
            Files.deleteIfExists(target)
            throw error
        }
    }

    private fun applyPrivatePermissions(path: Path, isDirectory: Boolean) {
        if (os == DesktopOs.Windows) return

        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?: error("POSIX permissions are unavailable for $path")
        view.setPermissions(
            PosixFilePermissions.fromString(if (isDirectory) "rwx------" else "rw-------")
        )
    }
}
