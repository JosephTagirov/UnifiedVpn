package org.olcbox.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import kotlin.coroutines.coroutineContext

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
        val windowsAppRoot = if (DesktopPaths.os == DesktopOs.Windows) {
            WindowsPortableUpdater.currentAppRoot()
        } else {
            null
        }
        val portableReplacementSupported = windowsAppRoot?.let {
            WindowsPortableUpdater.canReplaceInPlace(it)
        } == true
        val selectedAsset = selectJvmUpdateAsset(
            info = info,
            os = DesktopPaths.os,
            portableReplacementSupported = portableReplacementSupported
        )
        val expectedSha256 = UpdateDownloadSecurity.normalizeGithubSha256Digest(
            selectedAsset.digest
        )
        val file = withProxyAuthentication(proxy) {
            download(selectedAsset, expectedSha256, proxy, onProgress)
        }
        coroutineContext.ensureActive()
        val portableTargetRoot = if (
            portableReplacementSupported &&
            file.fileName.toString().endsWith(".zip", ignoreCase = true)
        ) {
            windowsAppRoot
        } else {
            null
        }

        if (portableTargetRoot != null) {
            val validatedArchive = WindowsPortableUpdater.validateArchive(
                archive = file,
                expectedVersion = info.version,
                expectedSha256 = expectedSha256
            )
            WindowsPortableUpdater.launch(
                validatedArchive = validatedArchive,
                targetRoot = portableTargetRoot,
                parentPid = ProcessHandle.current().pid()
            )
            JvmUpdateLaunchResult(
                message = "Applying Unified VPN ${info.version} and restarting...",
                shouldExitApplication = true
            )
        } else {
            val isWindowsInstaller = DesktopPaths.os == DesktopOs.Windows &&
                (file.fileName.toString().endsWith(".exe", ignoreCase = true) ||
                    file.fileName.toString().endsWith(".msi", ignoreCase = true))
            openWithSystemHandler(
                file = file,
                fallbackUrl = selectedAsset.downloadUrl,
                requireLocalOpen = isWindowsInstaller
            )
            JvmUpdateLaunchResult(
                message = if (isWindowsInstaller) {
                    "Opening verified Unified VPN ${info.version} installer..."
                } else {
                    "Opening ${selectedAsset.name}"
                },
                shouldExitApplication = isWindowsInstaller
            )
        }
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    internal suspend fun download(
        asset: AppUpdateAsset,
        expectedSha256: String,
        proxySettings: SubscriptionFetchProxy?,
        onProgress: (Float) -> Unit,
        connectionFactory: (URL, Proxy?) -> HttpURLConnection = { url, proxy ->
            (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection
        }
    ): Path = withContext(Dispatchers.IO) {
        UpdateDownloadSecurity.validateMetadataSize(asset.sizeBytes)
        Files.createDirectories(directory)
        val fileName = asset.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(180)
            ?.takeIf { it.isNotBlank() }
            ?: "unifiedvpn-update"
        val target = directory.resolve(fileName)

        val downloadUrl = URL(UpdateDownloadSecurity.requireHttpsUrl(asset.downloadUrl))
        val proxy = proxySettings?.let {
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(it.host, it.port)
            )
        }
        val connection = connectionFactory(downloadUrl, proxy)
        val partial = Files.createTempFile(directory, "unifiedvpn-update-", ".part")
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            val status = connection.responseCode
            require(status in 200..299) {
                "Update download failed with HTTP $status"
            }

            UpdateDownloadSecurity.requireHttpsUrl(connection.url.toString())
            val contentLength = UpdateDownloadSecurity.knownContentLength(connection.contentLengthLong)
            val total = contentLength ?: asset.sizeBytes ?: -1L
            val sha256 = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied = UpdateDownloadSecurity.addDownloadedBytes(copied, read)
                        output.write(buffer, 0, read)
                        sha256.update(buffer, 0, read)
                        if (total > 0L) {
                            reportProgress(
                                (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                                onProgress
                            )
                        }
                    }
                }
            }

            coroutineContext.ensureActive()
            asset.sizeBytes?.let { expectedSize ->
                require(copied == expectedSize) {
                    "Downloaded update size mismatch: expected $expectedSize bytes, got $copied"
                }
            }
            verifySha256(expectedSha256, sha256.digest())

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

    private fun openWithSystemHandler(
        file: Path,
        fallbackUrl: String,
        requireLocalOpen: Boolean
    ) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file.toFile())
            !requireLocalOpen && desktop?.isSupported(Desktop.Action.BROWSE) == true ->
                desktop.browse(URI(fallbackUrl))
            requireLocalOpen -> error("No local Windows installer handler is available")
            else -> error("No system file handler available for ${file.fileName}")
        }
    }

    private fun verifySha256(expectedSha256: String, actualBytes: ByteArray) {
        val actual = actualBytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        require(actual.equals(expectedSha256, ignoreCase = true)) {
            "Downloaded update SHA-256 mismatch"
        }
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}

internal fun selectJvmUpdateAsset(
    info: AppUpdateInfo,
    os: DesktopOs,
    portableReplacementSupported: Boolean
): AppUpdateAsset {
    if (os != DesktopOs.Windows) return info.asset
    if (info.asset.name.endsWith(".exe", ignoreCase = true) ||
        info.asset.name.endsWith(".msi", ignoreCase = true)
    ) {
        return info.asset
    }
    info.windowsInstallerAsset?.let { return it }
    if (portableReplacementSupported && info.asset.name.endsWith(".zip", ignoreCase = true)) {
        return info.asset
    }
    error(
        "This Unified VPN installation requires a matching Windows installer update, but the release does not provide one"
    )
}
