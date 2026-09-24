package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object OpenFluxBrowserProtocol {
    const val REQUEST_PREFIX = "OPENFLUX_BROWSER_VERIFY "
    const val MAX_MESSAGE_BYTES = 32_768
    private val request = Regex("^OPENFLUX_BROWSER_VERIFY ([0-9a-f]{32})$")
    private val accountCookieNames = setOf("session_id", "sessionid2", "yandex_login")

    fun requestId(line: String): String? = request.matchEntire(line)?.groupValues?.get(1)

    fun failure(id: String): String = buildJsonObject {
        put("id", id)
        put("error", "verification_failed")
    }.toString()

    fun helperRequest(id: String, documentUrl: String, directory: Path): String = buildJsonObject {
        put("id", id)
        put("document_url", documentUrl)
        put("user_data_dir", directory.toString())
    }.toString()

    fun checkedResponse(raw: String, id: String, documentUrl: String, now: Long = Instant.now().epochSecond): String? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return null
        return runCatching {
            val value = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            if (value["id"]?.jsonPrimitive?.takeIf { it.isString }?.content != id) return null
            if (value.keys == setOf("id", "error")) {
                return failure(id).takeIf { value["error"]?.jsonPrimitive?.takeIf { it.isString }?.content == "verification_failed" }
            }
            if (value.keys != setOf("id", "cookies")) return null
            val cookies = value["cookies"] as? JsonArray ?: return null
            if (cookies.size !in 1..64) return null
            val host = URI(documentUrl).host.lowercase()
            val identities = mutableSetOf<String>()
            var totalBytes = 0
            for (item in cookies) {
                val cookie = item as? JsonObject ?: return null
                if (cookie.keys != setOf("name", "value", "domain", "path", "secure", "expires")) return null
                fun string(key: String): String? = (cookie[key] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val name = string("name") ?: return null
                val cookieValue = string("value") ?: return null
                val domain = string("domain")?.lowercase() ?: return null
                val expires = cookie["expires"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: return null
                if (name.isEmpty() || name.length > 128 || name.lowercase() in accountCookieNames ||
                    name.any { it.code !in 33..126 || it in "()<>@,;:\\\"/[]?={}" } ||
                    cookieValue.toByteArray(Charsets.UTF_8).size > 4096 ||
                    cookieValue.any { it.code !in 32..126 || it in "\";\\" } ||
                    domain !in setOf("", host, ".$host", "yandex.ru", ".yandex.ru") || string("path") != "/" ||
                    cookie["secure"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull != true ||
                    expires != 0L && expires <= now
                ) return null
                val normalizedDomain = if (domain == host) "" else domain
                if (!identities.add(name.lowercase() + "\u0000" + normalizedDomain.removePrefix("."))) return null
                totalBytes += name.length + cookieValue.length + normalizedDomain.length + 1
                if (totalBytes > 24_576) return null
            }
            value.toString()
        }.getOrNull()
    }
}

internal class OpenFluxBrowserRequestGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val attempts = ArrayDeque<Pair<String, Long>>()

    fun allow(id: String): Boolean {
        val now = clock()
        while (attempts.isNotEmpty() && now - attempts.first().second >= 300_000) attempts.removeFirst()
        if (attempts.size >= 2 || attempts.any { it.first == id }) return false
        attempts.addLast(id to now)
        return true
    }
}

internal class DesktopOpenFluxBrowserBootstrap(
    private val log: (String) -> Unit,
    private val launchHelper: (Path) -> Process = ::launchWindowsBrowserHelper
) {
    suspend fun verify(id: String, documentUrl: String, nativeAlive: () -> Boolean): String = coroutineScope {
        val failure = OpenFluxBrowserProtocol.failure(id)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val directory = root.resolve("unifiedvpn-browser-${UUID.randomUUID().toString().replace("-", "")}")
        val helper = try {
            launchHelper(root)
        } catch (_: Exception) {
            log("OpenFlux browser verification helper is unavailable")
            return@coroutineScope failure
        }
        val output = async(Dispatchers.IO) {
            runCatching {
                val bytes = helper.inputStream.readNBytes(OpenFluxBrowserProtocol.MAX_MESSAGE_BYTES + 1)
                if (bytes.size > OpenFluxBrowserProtocol.MAX_MESSAGE_BYTES) null
                else Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
        }
        var response: String? = null
        var cleaned = false
        try {
            val input = OpenFluxBrowserProtocol.helperRequest(id, documentUrl, directory) + "\n"
            helper.outputStream.write(input.toByteArray(Charsets.UTF_8))
            helper.outputStream.flush()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
            response = withTimeoutOrNull(remainingMs) {
                while (helper.isAlive) {
                    if (!nativeAlive()) throw CancellationException("OpenFlux process stopped")
                    delay(100)
                }
                if (helper.exitValue() == 0) {
                    output.await()?.let { OpenFluxBrowserProtocol.checkedResponse(it, id, documentUrl) }
                } else {
                    log(when (helper.exitValue()) {
                        20 -> "OpenFlux browser verification requires Microsoft Edge WebView2 Runtime"
                        24 -> "OpenFlux browser verification timed out or was cancelled"
                        else -> "OpenFlux anonymous browser verification failed"
                    })
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log("OpenFlux browser verification failed")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { helper.outputStream.close() }
                if (!helper.waitFor(100, TimeUnit.MILLISECONDS)) {
                    // The helper owns a kill-on-close Job Object; this cannot terminate another browser.
                    helper.destroyForcibly()
                    helper.waitFor(1, TimeUnit.SECONDS)
                }
                runCatching { helper.inputStream.close() }
                output.cancel()
                if (helper.isAlive) log("OpenFlux private browser process could not be stopped")
                repeat(10) {
                    if (!cleaned) {
                        cleaned = runCatching { deleteOwnedDirectory(root, directory) }.isSuccess
                        if (!cleaned) delay(100)
                    }
                }
            }
        }
        if (!cleaned) log("OpenFlux private browser cleanup failed")
        if (cleaned && !helper.isAlive && nativeAlive()) response ?: failure else failure
    }

    private fun deleteOwnedDirectory(root: Path, directory: Path) {
        check(directory.parent == root && directory.fileName.toString().matches(Regex("unifiedvpn-browser-[0-9a-f]{32}")))
        if (!Files.exists(directory)) return
        // walkFileTree does not follow links; only this invocation's unpredictable directory is removed.
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private fun launchWindowsBrowserHelper(root: Path): Process =
    ProcessBuilder(DesktopNativeAssets.resolveWindowsBrowserHelper().toString())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply {
            environment().keys.filter { it.startsWith("WEBVIEW2_", ignoreCase = true) }
                .forEach { environment().remove(it) }
            environment()["TEMP"] = root.toString()
            environment()["TMP"] = root.toString()
        }.start()
