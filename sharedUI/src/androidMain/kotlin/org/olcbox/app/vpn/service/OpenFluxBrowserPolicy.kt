package org.olcbox.app.vpn.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal class OpenFluxBrowserCookie(val name: String, val value: String, val domain: String) {
    override fun toString(): String = "OpenFluxBrowserCookie([redacted])"
}

internal class OpenFluxBrowserSession(
    val cookies: List<OpenFluxBrowserCookie>,
    val userAgent: String,
    val classicDocument: OpenFluxClassicDocument? = null
) {
    init { require(isOpenFluxBrowserUserAgentValid(userAgent)) { "Invalid browser user agent" } }
    override fun toString(): String = "OpenFluxBrowserSession([redacted])"
}

internal class OpenFluxClassicDocument private constructor(val json: JsonObject) {
    val secrets: List<String>
        get() = listOf("token", "document_key", "source_url", "download_url", "balancer_url", "title")
            .mapNotNull { (json[it] as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty) }

    fun requireCurrent(documentUrl: String, nowSeconds: Long = System.currentTimeMillis() / 1000) {
        parse(json.toString(), documentUrl, nowSeconds)
    }

    override fun toString(): String = "OpenFluxClassicDocument([redacted])"

    companion object {
        fun parse(raw: String, documentUrl: String, nowSeconds: Long = System.currentTimeMillis() / 1000): OpenFluxClassicDocument {
            fun reject(stage: OpenFluxBrowserValidationStage = OpenFluxBrowserValidationStage.ClassicShape): Nothing =
                throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed, stage)
            if ((openFluxStrictUtf8Size(raw) ?: reject(OpenFluxBrowserValidationStage.ClassicEncoding)) > OPENFLUX_CLASSIC_DOCUMENT_MAX_BYTES) {
                reject(OpenFluxBrowserValidationStage.ClassicSize)
            }
            val value = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: reject()
            if (value.keys != setOf("source_url", "captured_at", "balancer_url", "token", "document_key",
                    "file_type", "download_url", "title", "edit")) reject()
            fun string(key: String): String = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reject()
            val captured = (value["captured_at"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: reject()
            if (captured < nowSeconds - 120 || captured > nowSeconds + 5) reject(OpenFluxBrowserValidationStage.ClassicTimestamp)
            if (string("source_url") != documentUrl || openFluxBrowserDocumentHost(documentUrl) == null) reject(OpenFluxBrowserValidationStage.ClassicSource)
            openFluxClassicBalancerFailure(string("balancer_url"))?.let { reject(it) }
            if (!isOpenFluxClassicDownloadReference(string("download_url"))) reject(OpenFluxBrowserValidationStage.ClassicDownload)
            fun visibleAscii(key: String, max: Int, stage: OpenFluxBrowserValidationStage): String = string(key).also {
                if (it.length !in 1..max || it.any { character -> character.code !in 0x21..0x7e }) reject(stage)
            }
            visibleAscii("token", 16_384, OpenFluxBrowserValidationStage.ClassicToken)
            if (visibleAscii("document_key", 512, OpenFluxBrowserValidationStage.ClassicKey) in setOf(".", "..")) reject(OpenFluxBrowserValidationStage.ClassicKey)
            if (!Regex("[A-Za-z0-9._-]{1,32}").matches(string("file_type"))) reject(OpenFluxBrowserValidationStage.ClassicFileType)
            val title = string("title")
            if ((openFluxStrictUtf8Size(title) ?: reject(OpenFluxBrowserValidationStage.ClassicTitle)) > 1024 ||
                title.any { Character.getType(it) == Character.CONTROL.toInt() }) reject(OpenFluxBrowserValidationStage.ClassicTitle)
            if ((value["edit"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true) reject(OpenFluxBrowserValidationStage.ClassicEdit)
            return OpenFluxClassicDocument(value)
        }
    }
}

private fun isOpenFluxClassicProviderHost(host: String): Boolean =
    listOf("yandex.ru", "yandex.net").any { host.lowercase().endsWith(".$it") }

internal fun isOpenFluxClassicBalancerOrigin(value: String): Boolean = openFluxClassicBalancerFailure(value) == null

private fun openFluxClassicBalancerFailure(value: String): OpenFluxBrowserValidationStage? {
    val invalidUrl = OpenFluxBrowserValidationStage.ClassicBalancerUrl
    if ((openFluxStrictUtf8Size(value) ?: return invalidUrl) !in 1..8192 ||
        value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return invalidUrl
    val uri = runCatching { URI(value) }.getOrNull() ?: return invalidUrl
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return invalidUrl
    // Only the classic provider's balancer names may contain underscores; URI.host is null for those names.
    val authority = uri.rawAuthority ?: return invalidUrl
    val host = authority.removeSuffix(":443")
    if (host.length !in 1..253 || !isOpenFluxClassicProviderHost(host) ||
        host.split('.').any { !OPENFLUX_BALANCER_LABEL.matches(it) }) return invalidUrl
    if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
        return OpenFluxBrowserValidationStage.ClassicBalancerOrigin
    }
    return null
}

internal fun isOpenFluxClassicDownloadReference(value: String): Boolean {
    val https = openFluxBrowserHttpsUri(value)
    if (https != null) return isOpenFluxClassicProviderHost(https.host) && https.rawFragment == null
    // OnlyOffice forwards this opaque provider-side reference; it must never be fetched locally.
    if (!value.startsWith("http://localhost:12701/disk/") ||
        (openFluxStrictUtf8Size(value) ?: return false) !in 1..8192 ||
        value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
    val reference = runCatching { URI(value) }.getOrNull() ?: return false
    if (reference.rawAuthority != "localhost:12701" || reference.rawUserInfo != null || reference.rawFragment != null) return false
    val path = openFluxStrictDecodedPath(reference) ?: return false
    return path.startsWith("/disk/") && path.length > "/disk/".length &&
        path.none { it.isWhitespace() || it.isISOControl() || it in "\\%?#" } &&
        path.split('/').none { it == "." || it == ".." }
}

private fun openFluxStrictDecodedPath(uri: URI): String? = runCatching {
    val raw = uri.rawPath.toByteArray(Charsets.UTF_8)
    val decoded = ByteArray(raw.size)
    var source = 0
    var target = 0
    while (source < raw.size) {
        if (raw[source].toInt() == '%'.code) {
            decoded[target++] = ((raw[source + 1].toInt().toChar().digitToInt(16) shl 4) or
                raw[source + 2].toInt().toChar().digitToInt(16)).toByte()
            source += 3
        } else {
            decoded[target++] = raw[source++]
        }
    }
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(decoded, 0, target)).toString()
}.getOrNull()

internal fun isOpenFluxBrowserUserAgentValid(value: String): Boolean =
    value.length in 1..1024 && value.trim() == value && value.all { it.code in 0x20..0x7e }

internal enum class OpenFluxBrowserFailure(val diagnostic: String) {
    Unsupported("Browser verification requires Android 9 or newer"),
    Failed("Browser verification failed"),
    Timeout("Browser verification timed out"),
    Network("Browser verification upstream network is unavailable"),
    Account("Browser verification refused an authenticated account session"),
    RateLimited("Browser verification retry limit reached"),
    Required("Open Yandex verification to continue connecting"),
    Cancelled("Browser verification was cancelled"),
    Expired("Browser verification expired; start a new connection")
}

internal enum class OpenFluxBrowserValidationStage(val code: String) {
    ClassicShape("classic_shape"), ClassicEncoding("classic_encoding"), ClassicSize("classic_size"),
    ClassicTimestamp("classic_timestamp"), ClassicSource("classic_source"), ClassicBalancerUrl("classic_balancer_url"),
    ClassicBalancerOrigin("classic_balancer_origin"), ClassicDownload("classic_download"), ClassicToken("classic_token"),
    ClassicKey("classic_key"), ClassicFileType("classic_file_type"), ClassicTitle("classic_title"), ClassicEdit("classic_edit")
}

internal fun openFluxBrowserValidationStage(code: String?): OpenFluxBrowserValidationStage? =
    OpenFluxBrowserValidationStage.entries.firstOrNull { it.code == code }

internal enum class OpenFluxBrowserDiagnosticStage(val code: String) {
    Request("request"), Prompt("prompt"), AwaitingUser("awaiting_user"),
    NetworkBinding("network_binding"), NetworkChanged("network_changed"),
    BrowserInitialization("browser_initialization"), Navigation("navigation"),
    NavigationRejected("navigation_rejected"), PageInspection("page_inspection"),
    DocumentCapture("document_capture"), CookieExtraction("cookie_extraction"), CookieCleanup("cookie_cleanup"),
    ReplyValidation("reply_validation"), ServiceDisconnected("service_disconnected"),
    UserCancelled("user_cancelled"), ActivityStopped("activity_stopped"), ActivityDestroyed("activity_destroyed"),
    ActivityInitialization("activity_initialization"), DeviceLocked("device_locked"),
    HttpAuthentication("http_authentication"), ClientCertificate("client_certificate"),
    RendererGone("renderer_gone"), JavascriptDialog("javascript_dialog"), DownloadRejected("download_rejected"),
    TlsNotYetValid("tls_not_yet_valid"), TlsExpired("tls_expired"), TlsHostnameMismatch("tls_hostname_mismatch"),
    TlsUntrusted("tls_untrusted"), TlsDateInvalid("tls_date_invalid"), TlsInvalid("tls_invalid"),
    WebViewHostLookup("webview_host_lookup"), WebViewAuthentication("webview_authentication"),
    WebViewConnect("webview_connect"), WebViewIo("webview_io"), WebViewTimeout("webview_timeout"),
    WebViewRedirectLoop("webview_redirect_loop"), WebViewScheme("webview_scheme"),
    WebViewTlsHandshake("webview_tls_handshake"), WebViewBadUrl("webview_bad_url"),
    WebViewFile("webview_file"), WebViewTooManyRequests("webview_too_many_requests"),
    WebViewUnsafeResource("webview_unsafe_resource"), WebViewOther("webview_other")
}

internal enum class OpenFluxBrowserHttpStatus(val code: String) {
    Unauthorized("unauthorized"), Forbidden("forbidden"), NotFound("not_found"),
    ProxyAuthentication("proxy_authentication"), Timeout("timeout"), RateLimited("rate_limited"),
    ClientError("client_error"), ServerError("server_error"), Other("other")
}

internal fun openFluxBrowserDiagnosticStage(code: String?): OpenFluxBrowserDiagnosticStage? =
    OpenFluxBrowserDiagnosticStage.entries.firstOrNull { it.code == code }

internal fun openFluxBrowserHttpStatus(code: String?): OpenFluxBrowserHttpStatus? =
    OpenFluxBrowserHttpStatus.entries.firstOrNull { it.code == code }

internal fun openFluxBrowserHttpStatus(status: Int): OpenFluxBrowserHttpStatus = when (status) {
    401 -> OpenFluxBrowserHttpStatus.Unauthorized
    403 -> OpenFluxBrowserHttpStatus.Forbidden
    404 -> OpenFluxBrowserHttpStatus.NotFound
    407 -> OpenFluxBrowserHttpStatus.ProxyAuthentication
    408 -> OpenFluxBrowserHttpStatus.Timeout
    429 -> OpenFluxBrowserHttpStatus.RateLimited
    in 400..499 -> OpenFluxBrowserHttpStatus.ClientError
    in 500..599 -> OpenFluxBrowserHttpStatus.ServerError
    else -> OpenFluxBrowserHttpStatus.Other
}

// Android callback integers are reduced to fixed categories, never descriptions or URLs.
internal fun openFluxBrowserTlsFailureStage(error: Int): OpenFluxBrowserDiagnosticStage = when (error) {
    0 -> OpenFluxBrowserDiagnosticStage.TlsNotYetValid
    1 -> OpenFluxBrowserDiagnosticStage.TlsExpired
    2 -> OpenFluxBrowserDiagnosticStage.TlsHostnameMismatch
    3 -> OpenFluxBrowserDiagnosticStage.TlsUntrusted
    4 -> OpenFluxBrowserDiagnosticStage.TlsDateInvalid
    else -> OpenFluxBrowserDiagnosticStage.TlsInvalid
}

internal fun openFluxBrowserWebViewFailureStage(error: Int): OpenFluxBrowserDiagnosticStage = when (error) {
    -2 -> OpenFluxBrowserDiagnosticStage.WebViewHostLookup
    -3, -4, -5 -> OpenFluxBrowserDiagnosticStage.WebViewAuthentication
    -6 -> OpenFluxBrowserDiagnosticStage.WebViewConnect
    -7 -> OpenFluxBrowserDiagnosticStage.WebViewIo
    -8 -> OpenFluxBrowserDiagnosticStage.WebViewTimeout
    -9 -> OpenFluxBrowserDiagnosticStage.WebViewRedirectLoop
    -10 -> OpenFluxBrowserDiagnosticStage.WebViewScheme
    -11 -> OpenFluxBrowserDiagnosticStage.WebViewTlsHandshake
    -12 -> OpenFluxBrowserDiagnosticStage.WebViewBadUrl
    -13, -14 -> OpenFluxBrowserDiagnosticStage.WebViewFile
    -15 -> OpenFluxBrowserDiagnosticStage.WebViewTooManyRequests
    -16 -> OpenFluxBrowserDiagnosticStage.WebViewUnsafeResource
    else -> OpenFluxBrowserDiagnosticStage.WebViewOther
}

internal class OpenFluxBrowserException(
    val reason: OpenFluxBrowserFailure,
    val validationStage: OpenFluxBrowserValidationStage? = null,
    val diagnosticStage: OpenFluxBrowserDiagnosticStage? = null,
    val httpStatus: OpenFluxBrowserHttpStatus? = null,
    val tlsResource: OpenFluxBrowserTlsResource? = null
) :
    IllegalStateException(reason.diagnostic)

internal fun openFluxBrowserReplyFailure(
    error: String?,
    validationStage: String?,
    diagnosticStage: String?,
    httpStatus: String?,
    localFailure: Throwable?,
    tlsResource: String? = null
): OpenFluxBrowserException {
    val local = (localFailure as? OpenFluxBrowserException).takeIf { error == null }
    return OpenFluxBrowserException(
        local?.reason ?: OpenFluxBrowserFailure.entries.firstOrNull { it.name == error } ?: OpenFluxBrowserFailure.Failed,
        local?.validationStage ?: openFluxBrowserValidationStage(validationStage),
        local?.diagnosticStage ?: openFluxBrowserDiagnosticStage(diagnosticStage) ?: OpenFluxBrowserDiagnosticStage.ReplyValidation,
        local?.httpStatus ?: openFluxBrowserHttpStatus(httpStatus),
        local?.tlsResource ?: openFluxBrowserTlsResourceFromCode(tlsResource)
    )
}

internal fun openFluxBrowserFailureLogLines(failure: OpenFluxBrowserException): List<String> = buildList {
    failure.validationStage?.let { add("OpenFlux: Browser metadata validation failed (stage=${it.code})") }
    if (failure.diagnosticStage != null || failure.httpStatus != null || failure.tlsResource != null) {
        val fields = listOfNotNull(
            "reason=${failure.reason.name}",
            failure.diagnosticStage?.let { "stage=${it.code}" },
            failure.tlsResource?.let { "resource=${it.code}" },
            failure.httpStatus?.let { "http=${it.code}" }
        )
        add("OpenFlux: Browser verification diagnostic (${fields.joinToString(", ")})")
    }
}

internal fun openFluxBrowserHttpLogLine(status: OpenFluxBrowserHttpStatus): String =
    "OpenFlux: Browser main-frame HTTP response (status=${status.code})"

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
    openFluxBrowserHttpsUri(url)?.takeIf { it.rawFragment == null }?.host?.lowercase()?.takeIf { it in OPENFLUX_DOCUMENT_HOSTS }

internal fun isOpenFluxBrowserNavigationAllowed(url: String): Boolean =
    openFluxBrowserDocumentHost(url) != null && !isOpenFluxBrowserLoginPath(url)

private fun isOpenFluxBrowserLoginPath(url: String): Boolean =
    runCatching { URI(url).path.split('/').any { it.lowercase() in setOf("login", "passport", "oauth", "authorize", "auth") } }
        .getOrDefault(true)

internal enum class OpenFluxBrowserNavigationPoll { AwaitInitialNavigation, InspectDocument, Reject }

internal fun openFluxBrowserNavigationPoll(currentUrl: String?, navigationObserved: Boolean): OpenFluxBrowserNavigationPoll =
    when {
        currentUrl.isNullOrEmpty() && !navigationObserved -> OpenFluxBrowserNavigationPoll.AwaitInitialNavigation
        currentUrl != null && isOpenFluxBrowserNavigationAllowed(currentUrl) -> OpenFluxBrowserNavigationPoll.InspectDocument
        else -> OpenFluxBrowserNavigationPoll.Reject
    }

internal fun isOpenFluxBrowserResourceAllowed(url: String): Boolean {
    val host = openFluxBrowserHttpsUri(url)?.host?.lowercase() ?: return false
    if (host.split('.').any { it == "passport" || it == "oauth" }) return false
    if (isOpenFluxBrowserLoginPath(url)) return false
    return OPENFLUX_RESOURCE_ROOTS.any { host == it || host.endsWith(".$it") }
}

internal enum class OpenFluxBrowserTlsResource(val code: String) {
    Document("document"), Disk("disk"), Hdrc("hdrc"), Mdd("mdd"), Static("static"),
    Provider("provider"), Unapproved("unapproved")
}

internal fun openFluxBrowserTlsResourceFromCode(code: String?): OpenFluxBrowserTlsResource? =
    OpenFluxBrowserTlsResource.entries.firstOrNull { it.code == code }

internal fun canContinueAfterBlockingOpenFluxTlsResource(failedUrl: String?, mainFrameUrl: String?): Boolean {
    if (mainFrameUrl == null || !isOpenFluxBrowserNavigationAllowed(mainFrameUrl)) return false
    val resource = failedUrl?.let(::openFluxBrowserHttpsUri) ?: return false
    return resource.rawFragment == null && resource.host.lowercase() in setOf("hdrc.yandex.net", "mdd.yandex.net")
}

internal fun openFluxBrowserBlockedTlsLogLine(stageCode: String?, resourceCode: String?): String? {
    val stage = openFluxBrowserDiagnosticStage(stageCode) ?: return null
    val resource = openFluxBrowserTlsResourceFromCode(resourceCode) ?: return null
    if (stage !in setOf(OpenFluxBrowserDiagnosticStage.TlsNotYetValid, OpenFluxBrowserDiagnosticStage.TlsExpired,
            OpenFluxBrowserDiagnosticStage.TlsHostnameMismatch, OpenFluxBrowserDiagnosticStage.TlsUntrusted,
            OpenFluxBrowserDiagnosticStage.TlsDateInvalid, OpenFluxBrowserDiagnosticStage.TlsInvalid) ||
        resource !in setOf(OpenFluxBrowserTlsResource.Hdrc, OpenFluxBrowserTlsResource.Mdd)) return null
    return "OpenFlux: Browser resource blocked (stage=${stage.code}, resource=${resource.code})"
}

internal fun openFluxBrowserTlsResource(url: String?): OpenFluxBrowserTlsResource {
    val value = url ?: return OpenFluxBrowserTlsResource.Unapproved
    val host = openFluxBrowserHttpsUri(value)?.host?.lowercase()
        ?: return OpenFluxBrowserTlsResource.Unapproved
    if (!isOpenFluxBrowserResourceAllowed(value)) return OpenFluxBrowserTlsResource.Unapproved
    return when (host) {
        "docs.yandex.ru" -> OpenFluxBrowserTlsResource.Document
        "disk.yandex.ru" -> OpenFluxBrowserTlsResource.Disk
        "hdrc.yandex.net" -> OpenFluxBrowserTlsResource.Hdrc
        "mdd.yandex.net" -> OpenFluxBrowserTlsResource.Mdd
        else -> if (host == "yastatic.net" || host.endsWith(".yastatic.net")) {
            OpenFluxBrowserTlsResource.Static
        } else {
            OpenFluxBrowserTlsResource.Provider
        }
    }
}

private fun openFluxBrowserHttpsUri(value: String): URI? {
    val size = openFluxStrictUtf8Size(value) ?: return null
    if (size !in 1..8192 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.port in listOf(-1, 443) &&
            it.rawUserInfo == null && !it.host.isNullOrBlank() && it.host.length <= 253 &&
            it.host.split('.').all { label -> OPENFLUX_DNS_LABEL.matches(label) } &&
            (it.rawAuthority.equals(it.host, ignoreCase = true) || it.rawAuthority.equals("${it.host}:443", ignoreCase = true))
    }
}

private fun openFluxStrictUtf8Size(value: String): Int? = runCatching {
    Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value)).remaining()
}.getOrNull()

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

internal fun openFluxBrowserResponse(
    id: String,
    cookies: List<OpenFluxBrowserCookie>?,
    userAgent: String? = null,
    classicDocument: OpenFluxClassicDocument? = null
): String {
    require(OPENFLUX_BROWSER_ID.matches(id)) { "Invalid browser verification request" }
    require(userAgent == null || cookies != null && isOpenFluxBrowserUserAgentValid(userAgent)) { "Invalid browser user agent" }
    require(classicDocument == null || cookies != null && userAgent != null) { "Invalid browser document" }
    val response = buildJsonObject {
        put("id", id)
        if (cookies == null) {
            put("error", "verification_failed")
        } else {
            if (userAgent != null) put("user_agent", userAgent)
            if (classicDocument != null) put("classic_document", classicDocument.json)
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
    if (response.toByteArray(Charsets.UTF_8).size > OPENFLUX_BROWSER_RESPONSE_MAX_BYTES) {
        throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
    }
    return response
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
            text.includes('\u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0431\u0440\u0430\u0443\u0437\u0435\u0440\u0430') ||
            text.includes('robot') || text.includes('\u0440\u043e\u0431\u043e\u0442') || text.includes('captcha')) return 'verification';
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

// Only the allowlisted fields cross IPC; source URL and capture time are supplied by the session owner.
internal val OPENFLUX_BROWSER_CLASSIC_DOCUMENT_SCRIPT = """
    (() => {
      try {
        const nodes = document.querySelectorAll('script#client-config');
        if (nodes.length !== 1 || !nodes[0].textContent || nodes[0].textContent.length > 2097152) return false;
        const root = JSON.parse(nodes[0].textContent);
        const office = root && root.officeActionData;
        if (office && office.office_online_editor_type === 'volga') return null;
        const editor = office && office.editor_config;
        const doc = editor && editor.document;
        if (!office || !editor || !doc) return false;
        const result = JSON.stringify({balancer_url: office.balancer_url, token: editor.token,
          document_key: doc.key, file_type: doc.fileType, download_url: doc.url,
          title: doc.title, edit: doc.permissions && doc.permissions.edit});
        return result.length <= 24576 ? result : false;
      } catch (_) { return false; }
    })()
""".trimIndent()

internal fun openFluxBrowserCapturedDocument(
    javascriptResult: String?,
    documentUrl: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000
): OpenFluxClassicDocument? {
    if (javascriptResult == "null") return null
    if (javascriptResult == null || javascriptResult.length > 2 * OPENFLUX_CLASSIC_DOCUMENT_MAX_BYTES + 2) {
        throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed,
            if (javascriptResult == null) OpenFluxBrowserValidationStage.ClassicShape else OpenFluxBrowserValidationStage.ClassicSize)
    }
    val result = runCatching {
        val encoded = Json.parseToJsonElement(javascriptResult) as? JsonPrimitive
        check(encoded?.isString == true)
        val fields = Json.parseToJsonElement(encoded.content) as JsonObject
        check(fields.keys == setOf("balancer_url", "token", "document_key", "file_type", "download_url", "title", "edit"))
        buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
            put("source_url", documentUrl)
            put("captured_at", nowSeconds)
        }
    }.getOrNull() ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed, OpenFluxBrowserValidationStage.ClassicShape)
    return OpenFluxClassicDocument.parse(result.toString(), documentUrl, nowSeconds)
}

internal const val OPENFLUX_BROWSER_MARKER = "OPENFLUX_BROWSER_VERIFY"
internal const val OPENFLUX_CLASSIC_DOCUMENT_MAX_BYTES = 24_576
internal const val OPENFLUX_BROWSER_RESPONSE_MAX_BYTES = 32_768
private val OPENFLUX_BROWSER_ID = Regex("[0-9a-f]{32}")
private val OPENFLUX_BROWSER_REQUEST = Regex("$OPENFLUX_BROWSER_MARKER ([0-9a-f]{32})")
private val OPENFLUX_DOCUMENT_HOSTS = setOf("docs.yandex.ru", "disk.yandex.ru")
private val OPENFLUX_RESOURCE_ROOTS = setOf("yandex.ru", "yandex.net", "yastatic.net", "yandexcloud.net")
private val OPENFLUX_ACCOUNT_COOKIES = setOf("session_id", "sessionid2", "yandex_login")
private val OPENFLUX_COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")
private val OPENFLUX_DNS_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private val OPENFLUX_BALANCER_LABEL = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")
