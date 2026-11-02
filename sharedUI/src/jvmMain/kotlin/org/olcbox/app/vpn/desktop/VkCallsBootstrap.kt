package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.*
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.VkCallsRuntimeConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** An authenticated support channel owned by the same encrypted subscription. */
internal class VkCallsBootstrap private constructor(
    val process: Process,
    val port: Int,
    private val username: String,
    private val password: String,
    private val configPath: Path,
    private val scope: CoroutineScope
) : AutoCloseable {
    val proxyUrl: String get() = "socks5://$username:$password@127.0.0.1:$port"

    fun sanitize(value: String): String = value.replace(proxyUrl, "<support-proxy>")
        .replace(username, "<redacted>").replace(password, "<redacted>")

    override fun close() {
        scope.cancel()
        try {
            if (process.isAlive) {
                runCatching {
                    process.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII))
                    process.outputStream.flush()
                    process.waitFor(1500, TimeUnit.MILLISECONDS)
                }
                if (process.isAlive) process.destroy()
                if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                }
            }
        } finally { runCatching { Files.deleteIfExists(configPath) } }
    }

    override fun toString(): String = "VkCallsBootstrap(credentials=[redacted])"

    companion object {
        fun findProfile(subscription: String?, entries: List<LocationEntry>): VpnProfileConfig? {
            if (subscription.isNullOrBlank() || !subscription.startsWith("https://")) return null
            return entries.firstOrNull { entry ->
                if (entry.subscriptionUrl != subscription || !entry.profile.isOpenFlux()) return@firstOrNull false
                val profile = entry.profile.normalized()
                val config = OpenFluxProfileConfig.parse(profile.rawConfig ?: profile.uri)
                config != null && config.isValid() && config.isSession &&
                    config.transport == OpenFluxProfileConfig.TRANSPORT_DIRECT
            }?.profile
        }

        suspend fun start(profile: VpnProfileConfig, excludedPorts: Set<Int>): VkCallsBootstrap {
            val port = LocalPortAllocator.select("127.0.0.1", 20001, excludedPorts)
            val username = UUID.randomUUID().toString().replace("-", "")
            val password = UUID.randomUUID().toString().replace("-", "")
            val socks = DesktopSocksProxySettings(host="127.0.0.1", port=port,
                username=username, password=password)
            val config = DesktopOpenFluxConfig.build(profile, socks)
            val path = DesktopPaths.writePrivateTempString(DesktopPaths.appDataDir().resolve("runtime"),
                "vk-support-", ".json", config)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var process: Process? = null
            var support: VkCallsBootstrap? = null
            try {
                val builder = ProcessBuilder(DesktopOpenFluxConfig.args(
                    DesktopNativeAssets.resolveOpenFluxBinary(session=true),path,session=true))
                    .redirectErrorStream(true)
                listOf("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy")
                    .forEach { builder.environment().remove(it) }
                process = builder.start()
                val running = process
                support = VkCallsBootstrap(running,port,username,password,path,scope)
                val ready = CompletableDeferred<Unit>()
                scope.launch {
                    try {
                        running.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (line == "OPENFLUX_READY") ready.complete(Unit)
                                if (line.startsWith("OPENFLUX_FATAL:"))
                                    ready.completeExceptionally(IllegalStateException("VK Calls support channel could not authenticate"))
                            }
                        }
                    } finally {
                        ready.completeExceptionally(IllegalStateException("VK Calls support channel stopped"))
                    }
                }
                check(withTimeoutOrNull(8_000) { ready.await(); true } == true) {
                    "VK Calls support channel timed out"
                }
                check(running.isAlive && VkCallsRuntimeConfig.probeSocks(port,username,password)) {
                    "VK Calls support authentication failed"
                }
                return support
            } catch (error: Throwable) {
                if (support != null) support.close() else {
                    scope.cancel();process?.destroyForcibly();Files.deleteIfExists(path)
                }
                throw error
            }
        }
    }
}
