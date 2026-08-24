package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.outputStream

data class JvmUpdateLaunchResult(
    val message: String,
    val shouldExitApplication: Boolean
)

class JvmUpdateInstaller(
    private val directory: Path = DesktopPaths.appDataDir().resolve("updates")
) {
    suspend fun downloadAndOpen(
        info: AppUpdateInfo,
        proxy: SubscriptionFetchProxy? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<JvmUpdateLaunchResult> = runCatching {
        val file = withProxyAuthentication(proxy) {
            download(info.asset, proxy, onProgress)
        }
        val windowsAppRoot = if (
            DesktopPaths.os == DesktopOs.Windows &&
            file.fileName.toString().endsWith(".zip", ignoreCase = true)
        ) {
            WindowsPortableUpdater.currentAppRoot()
        } else {
            null
        }

        if (windowsAppRoot != null) {
            val stagedRoot = WindowsPortableUpdater.stage(
                archive = file,
                stagingParent = directory.resolve("staging"),
                expectedVersion = info.version
            )
            WindowsPortableUpdater.launch(
                stagedRoot = stagedRoot,
                targetRoot = windowsAppRoot,
                workingDirectory = directory,
                parentPid = ProcessHandle.current().pid()
            )
            JvmUpdateLaunchResult(
                message = "Applying Unified VPN ${info.version} and restarting...",
                shouldExitApplication = true
            )
        } else {
            openWithSystemHandler(file, info.asset.downloadUrl)
            JvmUpdateLaunchResult(
                message = "Opening ${info.asset.name}",
                shouldExitApplication = false
            )
        }
    }

    private suspend fun download(
        asset: AppUpdateAsset,
        proxySettings: SubscriptionFetchProxy?,
        onProgress: (Float) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val fileName = asset.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf { it.isNotBlank() }
            ?: "unifiedvpn-update"
        val target = directory.resolve(fileName)
        val partial = directory.resolve("$fileName.part")
        Files.deleteIfExists(partial)

        val connection = if (proxySettings == null) {
            URL(asset.downloadUrl).openConnection()
        } else {
            URL(asset.downloadUrl).openConnection(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(proxySettings.host, proxySettings.port)
                )
            )
        } as HttpURLConnection

        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            val status = connection.responseCode
            require(status in 200..299) {
                "Update download failed with HTTP $status"
            }

            val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes ?: -1L
            val sha256 = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sha256.update(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            reportProgress(
                                (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                                onProgress
                            )
                        }
                    }
                }
            }

            asset.sizeBytes?.let { expectedSize ->
                require(copied == expectedSize) {
                    "Downloaded update size mismatch: expected $expectedSize bytes, got $copied"
                }
            }
            verifySha256(asset.digest, sha256.digest())

            try {
                Files.move(
                    partial,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            }
            reportProgress(1f, onProgress)
            target
        } catch (error: Throwable) {
            Files.deleteIfExists(partial)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun openWithSystemHandler(file: Path, fallbackUrl: String) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file.toFile())
            desktop?.isSupported(Desktop.Action.BROWSE) == true -> desktop.browse(URI(fallbackUrl))
            else -> error("No system file handler available for ${file.fileName}")
        }
    }

    private fun verifySha256(expectedDigest: String?, actualBytes: ByteArray) {
        if (expectedDigest.isNullOrBlank()) return
        val parts = expectedDigest.trim().split(':', limit = 2)
        require(parts.size == 2 && parts[0].equals("sha256", ignoreCase = true)) {
            "Unsupported update digest: ${parts.firstOrNull().orEmpty()}"
        }
        val actual = actualBytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        require(actual.equals(parts[1], ignoreCase = true)) {
            "Downloaded update SHA-256 mismatch"
        }
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
