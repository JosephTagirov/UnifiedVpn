package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.WindowsTrustedExecutables
import org.olcbox.app.vpn.ManagedEntryDns

/** A source-bound TLS fallback for the owned entry when another VPN blocks ordinary DNS. */
internal object WindowsPhysicalDohQuery {
    fun exchange(upstream: PhysicalUpstream, request: ByteArray): ByteArray? {
        if (DesktopPaths.os != DesktopOs.Windows || !validRequest(upstream, request) || Thread.currentThread().isInterrupted) return null
        var process: Process? = null
        var directory: Path? = null
        try {
            val parent = DesktopPaths.ensurePrivateDirectory(DesktopPaths.appDataDir().resolve("runtime"))
            val owned = DesktopPaths.ensurePrivateDirectory(Files.createTempDirectory(parent, "dns-"))
            directory = owned
            val answer = owned.resolve("answer.bin")
            val metadata = owned.resolve("metadata.txt")
            process = ProcessBuilder(command(WindowsTrustedExecutables.curlPath(), upstream, request, answer))
                .redirectOutput(metadata.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0 || Thread.currentThread().isInterrupted) return null
            if (!Files.isRegularFile(answer) || Files.size(answer) !in 12L..4096L ||
                !Files.isRegularFile(metadata) || Files.size(metadata) !in 1L..512L) return null
            return verifiedAnswer(upstream, request, Files.readAllBytes(answer), Files.readString(metadata))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        } finally {
            process?.let { child ->
                if (child.isAlive) {
                    val interrupted = Thread.interrupted()
                    try { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    finally { if (interrupted) Thread.currentThread().interrupt() }
                }
            }
            if (process?.isAlive != true) directory?.let { owned ->
                for (name in listOf("answer.bin", "metadata.txt")) runCatching { Files.deleteIfExists(owned.resolve(name)) }
                runCatching { Files.deleteIfExists(owned) }
            }
        }
    }

    internal fun validRequest(upstream: PhysicalUpstream, request: ByteArray): Boolean {
        if (upstream.index <= 0 || upstream.alias.isBlank() || physicalIpv4Bytes(upstream.ipv4Address) == null || request.size !in 12..512) return false
        val id = ((request[0].toInt() and 255) shl 8) or (request[1].toInt() and 255)
        return request.contentEquals(ManagedEntryDns.query(id))
    }

    internal fun command(curl: Path, upstream: PhysicalUpstream, request: ByteArray, output: Path): List<String> {
        require(validRequest(upstream, request))
        val query = Base64.getUrlEncoder().withoutPadding().encodeToString(request)
        return listOf(curl.toString(), "-q", "--silent", "--show-error", "--fail", "--proto", "=https",
            "--noproxy", "*", "--interface", upstream.ipv4Address, "--resolve", "dns.google:443:8.8.8.8",
            "--connect-timeout", "1.5", "--max-time", "3", "--max-filesize", "4096", "--max-redirs", "0",
            "--header", "Accept: application/dns-message", "--output", output.toString(),
            "--write-out", "%{local_ip}\n%{remote_ip}\n%{http_code}\n%{ssl_verify_result}\n",
            "https://dns.google/dns-query?dns=$query")
    }

    internal fun verifiedAnswer(upstream: PhysicalUpstream, request: ByteArray, answer: ByteArray, metadata: String): ByteArray? {
        if (!validRequest(upstream, request) || answer.size !in 12..4096 || metadata.length > 512) return null
        if (metadata.trim().lines() != listOf(upstream.ipv4Address, "8.8.8.8", "200", "0")) return null
        if (ManagedEntryDns.addresses(request, answer).none { it != ManagedEntryDns.BACKUP_IP }) return null
        return answer
    }
}
