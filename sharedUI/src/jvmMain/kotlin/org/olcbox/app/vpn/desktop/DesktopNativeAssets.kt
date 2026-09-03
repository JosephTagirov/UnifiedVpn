package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object DesktopNativeAssets {
    fun verifyRequiredAssets(): List<Path> {
        val assets = mutableListOf<Path>()
        assets.add(resolveOlcRtcBinary())
        assets.add(resolveOlcRtcDataDir().resolve("names"))
        assets.add(resolveOlcRtcDataDir().resolve("surnames"))
        when (DesktopPaths.os) {
            DesktopOs.Windows -> {
                assets.add(resolveWindowsTun2SocksBinary())
                assets.add(resolveSingBoxBinary())
                assets.add(resolveXrayBinary())
            }
            DesktopOs.Linux -> {
                assets.add(resolveHevSocks5TunnelBinary())
                assets.add(resolveSingBoxBinary())
                assets.add(resolveXrayBinary())
            }
            DesktopOs.Other -> Unit
        }
        return assets
    }

    fun resolveOlcRtcBinary(): Path {
        return resolveOlcRtcBinaryCandidates().first()
    }

    fun resolveOlcRtcBinaryCandidates(): List<Path> {
        val fileNames = olcRtcFileNames()
        return fileNames.mapNotNull { resolveOlcRtcBinaryOrNull(it) }.also {
            if (it.isEmpty()) {
                error("Bundled native binary is missing: ${fileNames.joinToString(", ") { name -> "native/$name" }}")
            }
        }
    }

    private fun resolveOlcRtcBinaryOrNull(fileName: String): Path? {
        System.getenv("OLCBOX_OLCRTC_EXE")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { Files.exists(it) }
            ?.let { return it }

        return try {
            resolveBinary(
                fileName = fileName,
                resourceName = "native/$fileName",
                candidates = olcRtcSourceCandidates(fileName)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun olcRtcFileNames(): List<String> {
        return when (DesktopPaths.os) {
            DesktopOs.Windows -> listOf("olcrtc-windows-amd64.exe")
            DesktopOs.Linux -> listOf("olcrtc-linux-${desktopArch()}")
            DesktopOs.Other -> error("Unified VPN desktop supports Windows and Linux")
        }
    }

    private fun olcRtcFileName(): String = olcRtcFileNames().first()

    fun resolveOlcRtcDataDir(): Path {
        val target = DesktopPaths.appDataDir().resolve("olcrtc-data")
        Files.createDirectories(target)
        copyDataFile("names", target)
        copyDataFile("surnames", target)
        return target
    }

    fun resolveHevSocks5TunnelBinary(): Path {
        val fileName = hevSocks5TunnelFileName()
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = hevSocks5TunnelSourceCandidates(fileName)
        )
    }

    fun resolveWindowsTun2SocksBinary(): Path {
        val fileName = windowsTun2SocksFileName()
        val binary = resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = windowsTun2SocksSourceCandidates(fileName)
        )
        copyRuntimeAsset("wintun.dll")
        return binary
    }

    fun resolveSingBoxBinary(): Path {
        val fileName = when (DesktopPaths.os) {
            DesktopOs.Windows -> "sing-box-awg-windows-amd64.exe"
            DesktopOs.Linux -> "sing-box-awg-linux-${desktopArch()}"
            DesktopOs.Other -> error("The bundled AmneziaWG sing-box engine requires Windows or Linux")
        }
        val explicitBinary = System.getenv("SING_BOX_AWG_BINARY")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path(it) }
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = listOfNotNull(explicitBinary) + desktopNativeResourceCandidates(fileName)
        )
    }

    fun resolveXrayBinary(): Path {
        val fileName = when (DesktopPaths.os) {
            DesktopOs.Windows -> "xray-windows-amd64.exe"
            DesktopOs.Linux -> "xray-linux-${desktopArch()}"
            DesktopOs.Other -> error("The bundled VLESS Xray engine requires Windows or Linux")
        }
        val explicitBinary = System.getenv("XRAY_BINARY")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path(it) }
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = listOfNotNull(explicitBinary) + desktopNativeResourceCandidates(fileName)
        )
    }

    private fun resolveBinary(
        fileName: String,
        resourceName: String,
        candidates: List<Path>
    ): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)

        if (copyBundledResource(resourceName, target)) {
            makeExecutable(target)
            return target
        }

        candidates.firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(target)
            return target
        }

        error("Bundled native binary is missing: $resourceName")
    }

    private fun copyDataFile(fileName: String, targetDir: Path) {
        val target = targetDir.resolve(fileName)
        val resourceName = "olcrtc-data/$fileName"
        if (copyBundledResource(resourceName, target)) {
            return
        }

        olcRtcDataSourceCandidates(fileName).firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            return
        }

        error("Bundled olcRTC data file is missing: $resourceName")
    }

    fun hevSocks5TunnelFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> "hev-socks5-tunnel-linux-${desktopArch()}"
            else -> error("hev-socks5-tunnel desktop binary is only used for TUN mode")
        }
    }

    fun windowsTun2SocksFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Windows -> "tun2socks-windows-amd64.exe"
            else -> error("tun2socks desktop binary is only used for Windows TUN mode")
        }
    }

    private fun desktopArch(): String {
        return when (DesktopPaths.arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported desktop architecture: ${DesktopPaths.arch}")
        }
    }

    private fun olcRtcSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("OLCRTC_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val explicitRepo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val defaultRepo = Path("..").resolve("olcrtc")
        return listOfNotNull(
            explicitBinary,
            explicitRepo
        ).flatMap { repoOrBinary ->
            if (repoOrBinary.fileName?.toString() == fileName || repoOrBinary.fileName?.toString() == fileName.removeSuffix(".exe")) {
                listOf(repoOrBinary)
            } else {
                repoCandidates(repoOrBinary, fileName)
            }
        } + repoCandidates(defaultRepo, fileName) + desktopNativeResourceCandidates(fileName)
    }

    private fun olcRtcDataSourceCandidates(fileName: String): List<Path> {
        val explicitRepo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val defaultRepo = Path("..").resolve("olcrtc")
        return listOfNotNull(explicitRepo, defaultRepo).flatMap { repo ->
            listOf(
                repo.resolve("data").resolve(fileName),
                repo.resolve("internal").resolve("names").resolve("data").resolve(fileName)
            )
        }
    }

    private fun repoCandidates(repo: Path, fileName: String): List<Path> {
        return listOf(
            repo.resolve("build").resolve(fileName),
            repo.resolve(fileName.removeSuffix(".exe")),
            repo.resolve("olcrtc")
        )
    }

    private fun hevSocks5TunnelSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("HEV_SOCKS5_TUNNEL_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(explicitBinary) + projectRootCandidates().flatMap { root ->
            val sourceBin = root.resolve("androidApp").resolve("src").resolve("main")
                .resolve("jni").resolve("hev-socks5-tunnel").resolve("bin")
            listOf(
                root.resolve("desktopApp").resolve("build").resolve("generated")
                    .resolve("desktopNativeResources").resolve("native").resolve(fileName),
                root.resolve("build").resolve("generated").resolve("desktopNativeResources")
                    .resolve("native").resolve(fileName),
                sourceBin.resolve("hev-socks5-tunnel.exe"),
                sourceBin.resolve("hev-socks5-tunnel")
            )
        }.distinct()
    }

    private fun windowsTun2SocksSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("TUN2SOCKS_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(explicitBinary) + desktopNativeResourceCandidates(fileName)
    }

    private fun copyRuntimeAsset(fileName: String): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)
        val resourceName = "native/$fileName"
        if (copyBundledResource(resourceName, target)) {
            return target
        }

        desktopNativeResourceCandidates(fileName)
            .firstOrNull { it.exists() }
            ?.let {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                return target
            }

        error("Bundled runtime asset is missing: $resourceName")
    }

    private fun copyBundledResource(resourceName: String, target: Path): Boolean {
        val classLoaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            javaClass.classLoader
        ).distinct()
        classLoaders.forEach { loader ->
            loader.getResourceAsStream(resourceName)?.use { resource ->
                Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING)
                return true
            }
        }

        javaClass.getResourceAsStream("/$resourceName")?.use { resource ->
            Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING)
            return true
        }

        bundledResourceDirectories()
            .map { it.resolve(resourceName) }
            .firstOrNull(Files::isRegularFile)
            ?.let { source ->
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                return true
            }

        bundledJarCandidates().forEach { jarPath ->
            val copied = runCatching {
                JarFile(jarPath.toFile()).use { jar ->
                    val entry = jar.getJarEntry(resourceName) ?: return@use false
                    jar.getInputStream(entry).use { resource ->
                        Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                }
            }.getOrDefault(false)
            if (copied) return true
        }

        return false
    }

    private fun bundledResourceDirectories(): List<Path> {
        val classPathEntries = System.getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .map { Path(it).toAbsolutePath().normalize() }
        val launcherDir = System.getProperty("jpackage.app-path")
            ?.takeIf(String::isNotBlank)
            ?.let(::Path)
            ?.toAbsolutePath()
            ?.normalize()
            ?.parent
        val codeSourceDir = runCatching {
            Path.of(javaClass.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize().parent
        }.getOrNull()

        return buildList {
            classPathEntries.filter(Files::isDirectory).forEach(::add)
            launcherDir?.let {
                add(it)
                add(it.resolve("app"))
            }
            codeSourceDir?.let(::add)
        }.distinct()
    }

    private fun bundledJarCandidates(): List<Path> {
        val directEntries = System.getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .map { Path(it).toAbsolutePath().normalize() }
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
        val directoryEntries = bundledResourceDirectories().flatMap { directory ->
            if (!Files.isDirectory(directory)) return@flatMap emptyList()
            runCatching {
                Files.list(directory).use { files ->
                    files.filter {
                        Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true)
                    }.toList()
                }
            }.getOrDefault(emptyList())
        }
        return (directEntries + directoryEntries).distinct()
    }

    private fun makeExecutable(path: Path) {
        if (DesktopPaths.os != DesktopOs.Windows) {
            path.toFile().setExecutable(true, true)
        }
    }

    private fun desktopNativeResourceCandidates(fileName: String): List<Path> {
        return projectRootCandidates().flatMap { root ->
            listOf(
                root.resolve("desktopApp").resolve("build").resolve("generated")
                    .resolve("desktopNativeResources").resolve("native").resolve(fileName),
                root.resolve("build").resolve("generated").resolve("desktopNativeResources")
                    .resolve("native").resolve(fileName)
            )
        }.distinct()
    }

    private fun projectRootCandidates(): List<Path> {
        val cwd = Path("").toAbsolutePath().normalize()
        return generateSequence(cwd) { it.parent }
            .take(5)
            .toList()
    }
}

fun verifyDesktopNativeAssets(): List<Path> = DesktopNativeAssets.verifyRequiredAssets()
