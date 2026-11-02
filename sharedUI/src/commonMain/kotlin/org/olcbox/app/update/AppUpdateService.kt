package org.olcbox.app.update

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy

@Serializable
enum class ReleaseChannel {
    Stable,
    Nightly
}

data class ReleaseMirror(
    val name: String,
    val repositoryUrl: String
) {
    val ownerRepo: String
        get() = repositoryUrl
            .removePrefix("https://github.com/")
            .removeSuffix("/")

    companion object {
        val GitHub = ReleaseMirror(
            name = "GitHub",
            repositoryUrl = "https://github.com/JosephTagirov/UnifiedVpn"
        )
    }
}

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val updatedAt: String? = null,
    val digest: String? = null,
    val fallbackUrls: List<String> = emptyList()
)

data class AppUpdateInfo(
    val channel: ReleaseChannel,
    val version: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val asset: AppUpdateAsset,
    val isUpdateAvailable: Boolean,
    val build: Long? = null,
    val windowsInstallerAsset: AppUpdateAsset? = null
)

class AppUpdateService(
    private val httpClient: HttpClient = createUpdateHttpClient(),
    private val mirror: ReleaseMirror = ReleaseMirror.GitHub,
    private val currentVersion: String = CurrentAppInfo.value.version,
    private val currentBuild: Long = CurrentAppInfo.value.build,
    private val platform: UpdatePlatform = UpdatePlatform.current()
) {
    suspend fun check(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): Result<AppUpdateInfo> = runCatching {
        val releases = coroutineScope {
            val server = async { runCatching { fetchServerRelease(channel, proxy) }.also { if(it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! } }
            val github = async { runCatching { fetchRelease(channel, proxy) }.also { if(it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! } }
            listOfNotNull(server.await().getOrNull(),github.await().getOrNull())
        }
        val release = releases.filter { selectAsset(it.assets,platform)!=null }.sortedWith { a,b ->
            val versionDifference=compareVersions(b.tagName.removePrefix("v"),a.tagName.removePrefix("v"))
            if(versionDifference!=0)versionDifference else
                (assetBuildNumber(selectAsset(b.assets,platform)!!.name) ?: 0).compareTo(assetBuildNumber(selectAsset(a.assets,platform)!!.name) ?: 0)
        }.firstOrNull()
            ?: error("Не удалось проверить обновления ни на сервере, ни в GitHub")
        val primary = selectAsset(release.assets, platform)
            ?: error(
                "No ${platform.assetToken.joinToString(" + ")} update asset in ${release.tagName}. " +
                        "Expected asset name containing ${platform.assetToken.joinToString(", ")}" +
                        platform.preferredExtensions.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " and ending with one of: ")
                            .orEmpty()
            )

        val alternatives = releases.filter { it.tagName==release.tagName }.flatMap { it.assets }
            .filter { it.name==primary.name && it.digest!=null && it.digest==primary.digest && it.browserDownloadUrl!=primary.downloadUrl }
            .map { it.browserDownloadUrl }.distinct()
        val asset = primary.copy(fallbackUrls=alternatives)
        val version = updateVersion(channel, release.tagName, asset)
        val build = assetBuildNumber(asset.name)
        val windowsInstallerAsset = if (platform.os == "windows") {
            selectWindowsInstallerAsset(
                assets = release.assets,
                platform = platform,
                requiredBuild = build
            )
        } else {
            null
        }
        AppUpdateInfo(
            channel = channel,
            version = version,
            htmlUrl = release.htmlUrl,
            publishedAt = release.publishedAt,
            asset = asset,
            isUpdateAvailable = isUpdateAvailable(
                channel = channel,
                releaseTag = version,
                currentVersion = currentVersion,
                releaseBuild = build,
                currentBuild = currentBuild
            ),
            build = build,
            windowsInstallerAsset = windowsInstallerAsset
        )
    }

    private suspend fun fetchServerRelease(channel: ReleaseChannel, proxy: SubscriptionFetchProxy?): GithubRelease {
        require(channel==ReleaseChannel.Stable) { "Server publishes stable updates" }
        val client=if(proxy==null) httpClient else createUpdateHttpClient(proxy)
        return try {
            withProxyAuthentication(proxy) {
                val response=client.get("https://panel.meesharr.space/vpn-control/updates/stable.json")
                require(response.status.value in 200..299) { "Server update request failed" }
                json.decodeFromString(GithubRelease.serializer(),response.bodyAsText())
            }
        } finally { if(client!==httpClient)client.close() }
    }

    suspend fun fetchRelease(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): GithubRelease {
        val client = if (proxy == null) {
            httpClient
        } else {
            createUpdateHttpClient(proxy)
        }

        return try {
            withProxyAuthentication(proxy) {
                fetchRelease(client, channel)
            }
        } finally {
            if (client !== httpClient) {
                client.close()
            }
        }
    }

    private suspend fun fetchRelease(client: HttpClient, channel: ReleaseChannel): GithubRelease {
        val endpoint = when (channel) {
            ReleaseChannel.Stable -> "https://api.github.com/repos/${mirror.ownerRepo}/releases/latest"
            ReleaseChannel.Nightly -> "https://api.github.com/repos/${mirror.ownerRepo}/releases/tags/nightly"
        }

        val response = client.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github+json")
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
            }
        }

        if (response.status.value !in 200..299) {
            error("GitHub release request failed with HTTP ${response.status.value}")
        }

        return json.decodeFromString(GithubRelease.serializer(), response.bodyAsText())
    }

    companion object {
        fun selectAsset(assets: List<GithubReleaseAsset>, platform: UpdatePlatform): AppUpdateAsset? {
            val asset = when (platform.os) {
                "android" -> selectAndroidAsset(assets, platform)
                else -> selectAssetByTokens(assets, platform.assetToken, platform.preferredExtensions)
            }

            return asset?.toAppUpdateAsset()
        }

        internal fun selectWindowsInstallerAsset(
            assets: List<GithubReleaseAsset>,
            platform: UpdatePlatform,
            requiredBuild: Long?
        ): AppUpdateAsset? {
            if (platform.os != "windows") return null
            val matchingAssets = assets.filter { candidate ->
                val name = candidate.name.lowercase()
                platform.assetToken.all { it in name } &&
                    name.endsWith(".exe") &&
                    (requiredBuild == null || assetBuildNumber(candidate.name) == requiredBuild)
            }
            return matchingAssets
                .maxByOrNull { assetBuildNumber(it.name) ?: Long.MIN_VALUE }
                ?.toAppUpdateAsset()
        }

        private fun GithubReleaseAsset.toAppUpdateAsset(): AppUpdateAsset = AppUpdateAsset(
            name = name,
            downloadUrl = browserDownloadUrl,
            sizeBytes = size,
            updatedAt = updatedAt,
            digest = digest
        )

        private fun selectAndroidAsset(
            assets: List<GithubReleaseAsset>,
            platform: UpdatePlatform
        ): GithubReleaseAsset? {
            val preferredExtensions = platform.preferredExtensions
            val exactAbiAsset = platform.androidArchTokens.firstNotNullOfOrNull { archToken ->
                selectAssetByTokens(assets, listOf("android", archToken), preferredExtensions)
            }
            if (exactAbiAsset != null) return exactAbiAsset

            val universalCandidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                "android" in name && knownAndroidArchTokens.none { it in name }
            }

            return selectPreferredAsset(universalCandidates, preferredExtensions)
        }

        private fun selectAssetByTokens(
            assets: List<GithubReleaseAsset>,
            tokens: List<String>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            val candidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                tokens.all { it in name }
            }

            return selectPreferredAsset(candidates, preferredExtensions)
        }

        private fun selectPreferredAsset(
            candidates: List<GithubReleaseAsset>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            val newestBuild = candidates.mapNotNull { assetBuildNumber(it.name) }.maxOrNull()
            val newestCandidates = if (newestBuild == null) {
                candidates
            } else {
                candidates.filter { assetBuildNumber(it.name) == newestBuild }
            }
            return preferredExtensions
                .firstNotNullOfOrNull { extension ->
                    newestCandidates
                        .filter { it.name.lowercase().endsWith(extension) }
                        .maxByOrNull { it.name }
                }
                ?: newestCandidates.maxByOrNull { it.name }
        }

        fun isUpdateAvailable(
            channel: ReleaseChannel,
            releaseTag: String,
            currentVersion: String,
            releaseBuild: Long? = null,
            currentBuild: Long = 0L
        ): Boolean {
            val release = releaseTag.removePrefix("v")
            if (channel == ReleaseChannel.Nightly && release == "nightly") return true

            val versionComparison = compareVersions(release, currentVersion)
            return when {
                versionComparison > 0 -> true
                versionComparison < 0 -> false
                else -> releaseBuild?.let { it > currentBuild } ?: false
            }
        }

        fun assetBuildNumber(assetName: String): Long? {
            return BUILD_NUMBER_PATTERN.find(assetName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
        }

        fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }

        private fun updateVersion(
            channel: ReleaseChannel,
            releaseTag: String,
            asset: AppUpdateAsset
        ): String {
            return when (channel) {
                ReleaseChannel.Stable -> releaseTag.removePrefix("v")
                ReleaseChannel.Nightly -> asset.name.versionToken() ?: releaseTag.removePrefix("v")
            }
        }

        private fun String.versionToken(): String? {
            return Regex("""(?:^|[-_])v?(\d+\.\d+\.\d+)(?:[-_.]|$)""")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
        }

        private val BUILD_NUMBER_PATTERN = Regex(
            pattern = "(?:^|[-_.])build[-_.]?(\\d{1,18})(?=[-_.]|$)",
            option = RegexOption.IGNORE_CASE
        )
    }
}

data class UpdatePlatform(
    val os: String,
    val arch: String
) {
    val assetToken: List<String>
        get() = when (os) {
            "windows" -> listOf("windows", "amd64")
            "linux" -> listOf("linux", arch)
            "android" -> listOf("android")
            else -> listOf(os, arch)
        }

    val androidArchTokens: List<String>
        get() = when (os) {
            "android" -> when (arch.lowercase()) {
                "armeabi-v7a", "armeabi" -> listOf("armeabi-v7a", "armeabi")
                "arm64", "arm64-v8a" -> listOf("arm64", "arm64-v8a")
                "amd64", "x86_64" -> listOf("amd64", "x86_64")
                "x86" -> listOf("x86")
                else -> emptyList()
            }
            else -> emptyList()
        }

    val preferredExtensions: List<String>
        get() = when (os) {
            "windows" -> listOf(".exe", ".msi", ".zip")
            "linux" -> listOf(".appimage")
            "android" -> listOf(".apk")
            else -> emptyList()
        }

    companion object {
        fun current(): UpdatePlatform = currentUpdatePlatform()
    }
}

expect fun currentUpdatePlatform(): UpdatePlatform

private val knownAndroidArchTokens = setOf(
    "armeabi-v7a",
    "armeabi",
    "arm64-v8a",
    "arm64",
    "x86_64",
    "amd64",
    "x86"
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList()
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val digest: String? = null
)

private val json = Json {
    ignoreUnknownKeys = true
}

private fun createUpdateHttpClient(proxy: SubscriptionFetchProxy? = null): HttpClient =
    createProxyHttpClient(
        subscriptionProxy = proxy,
        connectTimeoutMs = 5_000,
        requestTimeoutMs = 15_000,
        socketTimeoutMs = 15_000
    )
