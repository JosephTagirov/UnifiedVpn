package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    suspend fun start(
        helperBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = "",
        bypassHosts: List<String> = emptyList(),
        shouldContinue: () -> Boolean = { true }
    ): Process {
        val config = helperConfiguration(socksPort, socksUsername, socksPassword, bypassHosts)
        val broker = ProcessBuilder(helperBinary.toAbsolutePath().toString(), "--broker")
            .redirectErrorStream(true).start()
        try {
            broker.outputStream.write((config + "\n").toByteArray(Charsets.UTF_8))
            broker.outputStream.flush()
            addLog("Starting Windows TUN network helper; approve the administrator prompt if shown")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
            val line = StringBuilder()
            while (System.nanoTime() < deadline) {
                if (!shouldContinue()) throw CancellationException("TUN start superseded")
                while (broker.inputStream.available() > 0) {
                    val char = broker.inputStream.read().toChar()
                    if (char == '\n') {
                        val status = line.toString().trim()
                        line.clear()
                        if (status == "READY") {
                            check(broker.isAlive) { "Windows TUN helper exited before readiness" }
                            return broker
                        }
                        if (status.startsWith("ERROR ")) error(status)
                    } else {
                        check(line.length < 8192) { "Invalid TUN helper response" }
                        line.append(char)
                    }
                }
                check(broker.isAlive) { "Windows TUN helper exited or UAC was cancelled" }
                delay(100)
            }
            error("Windows TUN startup timed out")
        } catch (failure: Throwable) {
            withContext(NonCancellable) { stop(broker) }
            throw failure
        }
    }

    suspend fun stop(process: Process?) {
        if (process == null || !process.isAlive) return
        runCatching {
            process.outputStream.write("STOP\n".toByteArray(Charsets.UTF_8))
            process.outputStream.close()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (process.isAlive && System.nanoTime() < deadline) delay(100)
        stopProcess(process)
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        // Closing the broker's pipe revokes the helper lease. Its job object
        // owns privileged children; do not force-kill them before route cleanup.
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "Olcbox"
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        fun helperConfiguration(port: Int, username: String, password: String, endpoints: List<String>): String {
            require(DesktopSocksProxySettings.isValidPort(port))
            require(username.length <= 64 && password.length <= 64)
            require('\u0000' !in username && '\u0000' !in password)
            require(endpoints.size == 1) { "TUN needs one pinned VPN server address" }
            // The elevated component validates again and never receives paths or shell text.
            val value = buildJsonObject {
                put("port", port)
                put("username", username)
                put("password", password)
                put("endpoints", buildJsonArray { endpoints.forEach { add(it) } })
            }
            return Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), value)
        }
    }
}
