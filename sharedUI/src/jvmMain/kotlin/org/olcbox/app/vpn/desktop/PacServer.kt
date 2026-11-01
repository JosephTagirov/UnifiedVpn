package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PacServer(
    private val host: String = PAC_HOST,
    private val port: Int = PAC_PORT
) {
    private var server: HttpServer? = null
    @Volatile
    private var activePort: Int? = null
    @Volatile
    private var socksTarget = SocksTarget(LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT, "", "")
    private var executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
    }

    val url: String
        get() = "http://$host:${activePort ?: port}/proxy.pac"

    val boundPort: Int?
        get() = activePort

    fun start(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = ""
    ) {
        require(socksUsername.isBlank() && socksPassword.isBlank()) {
            "Chrome system proxy does not support SOCKS authentication; use local SOCKS mode with credentials"
        }
        updateSocksTarget(socksHost, socksPort, socksUsername, socksPassword)
        if (server != null) return
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
        }
        val httpServer = createServer()
        server = httpServer.also {
            httpServer.createContext("/proxy.pac") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.createContext("/") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.executor = executor
            httpServer.start()
            activePort = httpServer.address.port
        }
    }

    fun updateSocksTarget(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = ""
    ) {
        socksTarget = SocksTarget(
            host = socksHost.ifBlank { LOCAL_SOCKS_HOST },
            port = socksPort,
            username = socksUsername,
            password = socksPassword
        )
    }

    internal fun currentPacContent(): String {
        val target = socksTarget
        return generatePac(target.host, target.port, target.username, target.password)
    }

    fun stop() {
        server?.stop(0)
        server = null
        activePort = null
        executor.shutdownNow()
    }

    private fun createServer(): HttpServer {
        return try {
            HttpServer.create(InetSocketAddress(host, port), 0)
        } catch (error: BindException) {
            if (port == 0) throw error
            HttpServer.create(InetSocketAddress(host, 0), 0)
        }
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/x-ns-proxy-autoconfig; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class SocksTarget(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    companion object {
        const val PAC_HOST = "127.0.0.1"
        const val PAC_PORT = 10809
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val LOCAL_SOCKS_PORT = 10808

        fun generatePac(
            socksHost: String = LOCAL_SOCKS_HOST,
            socksPort: Int = LOCAL_SOCKS_PORT,
            socksUsername: String = "",
            socksPassword: String = ""
        ): String {
            require(socksUsername.isBlank() && socksPassword.isBlank()) {
                "Chrome system proxy does not support SOCKS authentication. Use local SOCKS mode with credentials, or an unauthenticated loopback proxy."
            }
            val target = socksProxyTarget(socksHost, socksPort, socksUsername, socksPassword)
            return """
function hostEqOrSub(host, domain) {
  return host === domain || host.slice(-(domain.length + 1)) === "." + domain;
}
function localAddress(host) {
  if (host === "localhost" || host === "::1") return true;
  if (host.indexOf(":") !== -1) {
    return /^(fc|fd)[0-9a-f]{2}:/.test(host) || /^fe[89ab][0-9a-f]:/.test(host);
  }
  var p = host.split(".");
  if (p.length !== 4) return false;
  for (var i = 0; i < 4; i++) {
    if (!/^[0-9]{1,3}$/.test(p[i]) || +p[i] > 255) return false;
    p[i] = +p[i];
  }
  return p[0] === 127 || p[0] === 10 ||
    (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
    (p[0] === 192 && p[1] === 168) ||
    (p[0] === 169 && p[1] === 254) ||
    (p[0] === 100 && p[1] >= 64 && p[1] <= 127);
}
function FindProxyForURL(url, host) {
  host = host.toLowerCase().replace(/\.$/, "");
  if (host.charAt(0) === "[" && host.slice(-1) === "]") host = host.slice(1, -1);
  if ((host.indexOf(":") === -1 && isPlainHostName(host)) || localAddress(host)) return "DIRECT";
  var direct = ["ru", "su", "xn--p1ai", "рф", "yandex.com", "yandex.net",
    "yastatic.net", "kinopoisk.com", "vk.com", "vk.me", "vk-portal.net", "userapi.com",
    "mycdn.me", "ok.live", "tbank.com", "tinkoff.com", "otpbank.ru", "psbank.ru",
    "max.ru", "oplata.info", "matreshka.tv"];
  for (var i = 0; i < direct.length; i++) {
    if (hostEqOrSub(host, direct[i])) return "DIRECT";
  }
  return "$target";
}
            """.trimIndent()
        }

        private fun socksProxyTarget(
            socksHost: String,
            socksPort: Int,
            socksUsername: String,
            socksPassword: String
        ): String {
            val target = socksProxyUri(socksHost, socksPort, socksUsername, socksPassword)

            return "SOCKS5 $target"
        }

        internal fun socksProxyUri(
            socksHost: String = LOCAL_SOCKS_HOST,
            socksPort: Int = LOCAL_SOCKS_PORT,
            socksUsername: String = "",
            socksPassword: String = ""
        ): String {
            val normalizedHost = socksHost.ifBlank { LOCAL_SOCKS_HOST }
            val hostPort = if (':' in normalizedHost && !normalizedHost.startsWith("[")) {
                "[$normalizedHost]:$socksPort"
            } else {
                "$normalizedHost:$socksPort"
            }
            val userInfo = if (socksUsername.isNotBlank()) {
                "${socksUsername.percentEncodeUserInfo()}:${socksPassword.percentEncodeUserInfo()}@"
            } else {
                ""
            }
            return "$userInfo$hostPort"
        }

        private fun String.percentEncodeUserInfo(): String {
            val bytes = toByteArray(StandardCharsets.UTF_8)
            return buildString(bytes.size) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    val char = value.toChar()
                    if (char.isUnreservedUserInfoChar()) {
                        append(char)
                    } else {
                        append('%')
                        append(value.toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }

        private fun Char.isUnreservedUserInfoChar(): Boolean {
            return this in 'A'..'Z' ||
                    this in 'a'..'z' ||
                    this in '0'..'9' ||
                    this == '-' ||
                    this == '.' ||
                    this == '_' ||
                    this == '~'
        }
    }
}
