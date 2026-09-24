package org.olcbox.app.vpn.service

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

internal class OpenFluxBrowserCookie(val name: String, val value: String, val domain: String) {
    override fun toString(): String = "OpenFluxBrowserCookie([redacted])"
}

internal enum class OpenFluxBrowserFailure(val diagnostic: String) {
    Unsupported("Browser verification requires Android 9 or newer"),
    Failed("Browser verification failed"),
    Timeout("Browser verification timed out"),
    Network("Browser verification upstream network is unavailable"),
    Account("Browser verification refused an authenticated account session"),
    RateLimited("Browser verification retry limit reached")
}

internal class OpenFluxBrowserException(val reason: OpenFluxBrowserFailure) :
    IllegalStateException(reason.diagnostic)

internal fun openFluxBrowserRequestId(line: String): String? =
    OPENFLUX_BROWSER_REQUEST.matchEntire(line)?.groupValues?.get(1)

internal fun openFluxBrowserDesktopUserAgent(defaultAgent: String): String? {
    if (defaultAgent.length > 2048 || defaultAgent.any { it.isISOControl() }) return null
    val versions = Regex("(?:^| )Chrome/(\\d{1,3}(?:\\.\\d{1,5}){3})(?= |$)")
        .findAll(defaultAgent).toList()
    if (versions.size != 1) return null
    val version = versions.single().groupValues[1]
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$version Safari/537.36"
}

internal fun openFluxBrowserDocumentHost(url: String): String? =
    openFluxBrowserHttpsUri(url)?.host?.lowercase()?.takeIf { it in OPENFLUX_DOCUMENT_HOSTS }

internal fun isOpenFluxBrowserNavigationAllowed(url: String): Boolean =
    openFluxBrowserDocumentHost(url) != null

internal fun isOpenFluxBrowserResourceAllowed(url: String): Boolean {
    val host = openFluxBrowserHttpsUri(url)?.host?.lowercase() ?: return false
    if (host.split('.').any { it == "passport" || it == "oauth" }) return false
    return OPENFLUX_RESOURCE_ROOTS.any { host == it || host.endsWith(".$it") }
}

private fun openFluxBrowserHttpsUri(value: String): URI? {
    if (value.length !in 1..8192 || value.any { it.isWhitespace() || it.isISOControl() }) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.port in listOf(-1, 443) &&
            it.rawUserInfo == null && !it.host.isNullOrBlank()
    }
}

internal fun parseOpenFluxBrowserCookies(header: String, documentUrl: String): List<OpenFluxBrowserCookie> {
    val host = openFluxBrowserDocumentHost(documentUrl)
        ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    if (header.isEmpty() || header.length > 16_384 || header.any { it.isISOControl() || it.code > 0x7e }) {
        throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    }
    val names = mutableSetOf<String>()
    val cookies = header.split(';').map { raw ->
        val pair = raw.trim()
        val separator = pair.indexOf('=')
        if (separator <= 0) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
        val name = pair.substring(0, separator)
        val value = pair.substring(separator + 1)
        if (name.lowercase() in OPENFLUX_ACCOUNT_COOKIES) {
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Account)
        }
        if (!OPENFLUX_COOKIE_NAME.matches(name) || !names.add(name) || value.length > 4096 ||
            value.any { it.code !in 0x21..0x7e || it in "\";,\\" }
        ) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
        OpenFluxBrowserCookie(name, value, host)
    }
    if (cookies.size !in 1..32) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    return cookies
}

internal fun parseOpenFluxBrowserCookieHeaders(headers: Map<String, String>): List<OpenFluxBrowserCookie> {
    if (headers.keys.any { it !in OPENFLUX_DOCUMENT_HOSTS } ||
        headers.values.sumOf { it.length.toLong() } > 16_384
    ) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    val cookies = headers.flatMap { (host, header) ->
        if (header.isEmpty()) emptyList() else parseOpenFluxBrowserCookies(header, "https://$host/")
    }
    if (cookies.size !in 1..32) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    return cookies
}

internal fun openFluxBrowserResponse(id: String, cookies: List<OpenFluxBrowserCookie>?): String {
    require(OPENFLUX_BROWSER_ID.matches(id)) { "Invalid browser verification request" }
    return buildJsonObject {
        put("id", id)
        if (cookies == null) {
            put("error", "verification_failed")
        } else {
            put("cookies", buildJsonArray {
                cookies.forEach { cookie ->
                    add(buildJsonObject {
                        put("name", cookie.name)
                        put("value", cookie.value)
                        put("domain", cookie.domain)
                        put("path", "/")
                        put("secure", true)
                        put("expires", 0)
                    })
                }
            })
        }
    }.toString()
}

internal class OpenFluxBrowserRequestLimiter(private val nowMillis: () -> Long) {
    private val attempts = ArrayDeque<Long>()

    @Synchronized
    fun admit(): Boolean {
        val now = nowMillis()
        while (attempts.isNotEmpty() && now - attempts.first() >= 300_000L) attempts.removeFirst()
        if (attempts.size >= 2) return false
        attempts.addLast(now)
        return true
    }
}

// Diagnostics return fixed categories, never document keys, editor tokens or HTML.
internal enum class OpenFluxBrowserPageState { Unknown, Loading, Config, Verification, Other }

internal fun openFluxBrowserPageState(result: String?): OpenFluxBrowserPageState = when (result) {
    "\"loading\"" -> OpenFluxBrowserPageState.Loading
    "\"config\"" -> OpenFluxBrowserPageState.Config
    "\"verification\"" -> OpenFluxBrowserPageState.Verification
    "\"other\"" -> OpenFluxBrowserPageState.Other
    else -> OpenFluxBrowserPageState.Unknown
}

internal val OPENFLUX_BROWSER_PAGE_STATE_SCRIPT = """
    (() => {
      try {
        if (document.getElementById('client-config')) return 'config';
        const text = (document.body && document.body.innerText || '').slice(0, 65536).toLowerCase();
        if (text.includes('verify your browser') || text.includes('browser verification') ||
            text.includes('\u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0431\u0440\u0430\u0443\u0437\u0435\u0440\u0430')) return 'verification';
        return document.readyState === 'loading' ? 'loading' : 'other';
      } catch (_) { return 'unknown'; }
    })()
""".trimIndent()

// Cookie bootstrap only: the native transport must authorize access before reporting Ready.
internal val OPENFLUX_BROWSER_BOOTSTRAP_READY_SCRIPT = """
    () => {
      try {
        const nodes = document.querySelectorAll('script#client-config');
        if (nodes.length !== 1 || !nodes[0].textContent || nodes[0].textContent.length > 2097152) return false;
        const root = JSON.parse(nodes[0].textContent);
        const office = root && root.officeActionData;
        const editor = office && office.editor_config;
        const doc = editor && editor.document;
        const nonempty = value => typeof value === 'string' && value.length > 0;
        if (!office || !editor) return false;
        if (office.office_online_editor_type !== 'volga') {
          return !!(doc && nonempty(office.balancer_url) && nonempty(editor.token) &&
            nonempty(doc.key) && doc.permissions && doc.permissions.edit === true);
        }
        const denied = rights => Array.isArray(rights) && !rights.includes('write');
        if (denied(root.rights) || denied(root.editorParams && root.editorParams.rights) ||
            (doc && doc.permissions && doc.permissions.edit === false)) return false;
        if (!nonempty(office.access_token) || editor.documentType !== 'text' ||
            !nonempty(office.action_url) || office.action_url.length > 8192 ||
            /[\u0000-\u0020\u007f]/.test(office.action_url)) return false;
        const action = new URL(office.action_url);
        return action.protocol === 'https:' && action.hostname === 'volga.yandex.ru' &&
          (action.port === '' || action.port === '443') && action.username === '' &&
          action.password === '' && action.hash === '';
      } catch (_) { return false; }
    }
""".trimIndent()

internal const val OPENFLUX_BROWSER_MARKER = "OPENFLUX_BROWSER_VERIFY"
internal const val OPENFLUX_BROWSER_TIMEOUT_MS = 45_000L
private val OPENFLUX_BROWSER_ID = Regex("[0-9a-f]{32}")
private val OPENFLUX_BROWSER_REQUEST = Regex("$OPENFLUX_BROWSER_MARKER ([0-9a-f]{32})")
private val OPENFLUX_DOCUMENT_HOSTS = setOf("docs.yandex.ru", "disk.yandex.ru")
private val OPENFLUX_RESOURCE_ROOTS = setOf("yandex.ru", "yandex.net", "yastatic.net", "yandexcloud.net")
private val OPENFLUX_ACCOUNT_COOKIES = setOf("session_id", "sessionid2", "yandex_login")
private val OPENFLUX_COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")
