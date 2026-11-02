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
import java.nio.CharBuffer
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
    private const val MAX_CLASSIC_DOCUMENT_BYTES = 24_576
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
            if (!value.keys.containsAll(setOf("id", "cookies")) ||
                !setOf("id", "cookies", "user_agent", "classic_document").containsAll(value.keys)
            ) return null
            if ("user_agent" in value) {
                val agent = (value["user_agent"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (agent.length !in 1..1024 || agent.trim() != agent || agent.any { it.code !in 0x20..0x7e }) return null
            }
            val cookies = value["cookies"] as? JsonArray ?: return null
            if (cookies.size !in 1..64) return null
            val host = documentUri(documentUrl)?.host?.lowercase() ?: return null
            if ("classic_document" in value &&
                !classicDocumentAllowed(value["classic_document"] as? JsonObject, documentUrl, now)
            ) return null
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

    private fun utf8Length(value: String): Int? = runCatching {
        Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining()
    }.getOrNull()

    private fun urlTextAllowed(value: String): Boolean {
        val bytes = utf8Length(value) ?: return false
        return bytes in 1..8192 &&
            value.none { it.isWhitespace() || Character.isISOControl(it) || it == '\\' } &&
            !Regex("%(?![0-9a-fA-F]{2})").containsMatchIn(value)
    }

    private fun strictHttpsUri(value: String): URI? {
        if (!urlTextAllowed(value)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in setOf(-1, 443) ||
            uri.rawAuthority?.matches(Regex("[A-Za-z0-9.-]+(?::443)?")) != true || host.length > 253 ||
            host.split('.').any { !it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) }
        ) return null
        return uri
    }

    private fun documentUri(value: String): URI? = strictHttpsUri(value)?.takeIf {
        it.host.lowercase() in setOf("docs.yandex.ru", "disk.yandex.ru") && it.rawPath.length > 1
    }

    private fun classicResourceAllowed(value: String, originOnly: Boolean): Boolean {
        val uri = strictHttpsUri(value) ?: return false
        val host = uri.host.lowercase()
        return (host.endsWith(".yandex.ru") || host.endsWith(".yandex.net")) &&
            (!originOnly || uri.rawPath.isEmpty() && uri.rawQuery == null)
    }

    private fun classicBalancerAllowed(value: String): Boolean {
        if (!urlTextAllowed(value)) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawPath.isNotEmpty() ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null
        ) return false
        // URI.host is null for the provider's underscore-bearing balancer authority.
        val host = Regex("([A-Za-z0-9_.-]+)(?::443)?").matchEntire(uri.rawAuthority ?: return false)
            ?.groupValues?.get(1) ?: return false
        return host.length <= 253 && host.split('.').all {
            it.matches(Regex("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?"))
        } && (host.lowercase().endsWith(".yandex.ru") || host.lowercase().endsWith(".yandex.net"))
    }

    private fun classicDocumentReferenceAllowed(value: String): Boolean {
        if (classicResourceAllowed(value, originOnly = false)) return true
        if (!urlTextAllowed(value) || !value.startsWith("http://localhost:12701/disk/") || '#' in value) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "http" || uri.rawAuthority != "localhost:12701" || uri.rawUserInfo != null || uri.rawFragment != null) return false
        // Validate percent-encoded UTF-8 before URI.path can replace malformed bytes.
        for (encoded in Regex("(?:%[0-9a-fA-F]{2})+").findAll(uri.rawPath)) {
            val bytes = ByteArray(encoded.value.length / 3) { index ->
                encoded.value.substring(index * 3 + 1, index * 3 + 3).toInt(16).toByte()
            }
            if (runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)) }.isFailure) return false
        }
        val path = uri.path
        // OnlyOffice consumes this reference remotely; this is not a fetch destination policy.
        return path.startsWith("/disk/") && path.length > 6 &&
            path.none { it.isWhitespace() || Character.isISOControl(it) || it in "\\%?#" } &&
            path.split('/').none { it == "." || it == ".." }
    }

    private fun classicDocumentAllowed(value: JsonObject?, documentUrl: String, now: Long): Boolean {
        if (value == null || value.keys != setOf("source_url", "captured_at", "balancer_url", "token",
                "document_key", "file_type", "download_url", "title", "edit") ||
            (utf8Length(value.toString()) ?: return false) > MAX_CLASSIC_DOCUMENT_BYTES
        ) return false
        fun string(key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun visibleAscii(text: String, limit: Int): Boolean = text.length in 1..limit &&
            text.all { it.code in 0x21..0x7e }
        val sourceUrl = string("source_url") ?: return false
        val capturedAt = (value["captured_at"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return false
        val balancerUrl = string("balancer_url") ?: return false
        val token = string("token") ?: return false
        val documentKey = string("document_key") ?: return false
        val fileType = string("file_type") ?: return false
        val downloadUrl = string("download_url") ?: return false
        val title = string("title") ?: return false
        return sourceUrl == documentUrl && documentUri(sourceUrl) != null &&
            capturedAt >= now - 120 && capturedAt <= now + 5 &&
            classicBalancerAllowed(balancerUrl) && visibleAscii(token, 16_384) &&
            visibleAscii(documentKey, 512) && documentKey !in setOf(".", "..") &&
            fileType.matches(Regex("[A-Za-z0-9._-]{1,32}")) &&
            classicDocumentReferenceAllowed(downloadUrl) &&
            (utf8Length(title) ?: return false) <= 1024 && title.none { Character.isISOControl(it) } &&
            (value["edit"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
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
    private val verificationTimeoutMs: Long = 45_000,
    private val interactive: Boolean = false,
    private val launchHelper: ((Path) -> Process)? = null
) {
    suspend fun verify(id: String, documentUrl: String, nativeAlive: () -> Boolean): String = coroutineScope {
        val failure = OpenFluxBrowserProtocol.failure(id)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(verificationTimeoutMs)
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val directory = root.resolve("unifiedvpn-browser-${UUID.randomUUID().toString().replace("-", "")}")
        val helper = try {
            launchHelper?.invoke(root) ?: launchWindowsBrowserHelper(root, interactive)
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
            var helperWaitCompleted = false
            response = withTimeoutOrNull(remainingMs) {
                while (helper.isAlive) {
                    if (!nativeAlive()) throw CancellationException("OpenFlux process stopped")
                    delay(100)
                }
                val received = if (helper.exitValue() == 0) {
                    output.await()?.let { OpenFluxBrowserProtocol.checkedResponse(it, id, documentUrl) }
                        .also {
                            when (it) {
                                null -> log("OpenFlux browser verification response was rejected")
                                failure -> log("OpenFlux anonymous browser verification failed")
                                else -> Unit
                            }
                        }
                } else {
                    log(when (helper.exitValue()) {
                        20 -> "OpenFlux browser verification requires Microsoft Edge WebView2 Runtime"
                        24 -> "OpenFlux browser verification timed out or was cancelled"
                        else -> "OpenFlux anonymous browser verification failed"
                    })
                    null
                }
                helperWaitCompleted = true
                received
            }
            if (!helperWaitCompleted) log("OpenFlux browser verification timed out or was cancelled")
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
        if (cleaned && !helper.isAlive && nativeAlive()) {
            if (response != null && response != failure) log("OpenFlux browser verification response accepted")
            response ?: failure
        } else failure
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

internal fun desktopOpenFluxBrowserCommand(helper: Path, interactive: Boolean): List<String> =
    listOf(helper.toString()) + if (interactive) listOf("--interactive") else emptyList()

private fun launchWindowsBrowserHelper(root: Path, interactive: Boolean): Process =
    ProcessBuilder(desktopOpenFluxBrowserCommand(DesktopNativeAssets.resolveWindowsBrowserHelper(), interactive))
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply {
            environment().keys.filter { it.startsWith("WEBVIEW2_", ignoreCase = true) }
                .forEach { environment().remove(it) }
            environment()["TEMP"] = root.toString()
            environment()["TMP"] = root.toString()
        }.start()
