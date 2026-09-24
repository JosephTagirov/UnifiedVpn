package org.olcbox.app.vpn.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Owns only the app's dedicated verification WebView store, never the user's browser. */
class OpenFluxBrowserVerificationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid()) {
            checkpoint("request_rejected")
            return@Handler true
        }
        when (message.what) {
            OPENFLUX_BROWSER_VERIFY -> begin(message)
            OPENFLUX_BROWSER_CANCEL -> active?.takeIf { it.reply.binder == message.replyTo?.binder }
                ?.let { finish(it, null, OpenFluxBrowserFailure.Failed) }
        }
        true
    })
    @Volatile private var active: Session? = null
    private var cleaning = false
    private var initializationFailure: OpenFluxBrowserFailure? = null
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onCreate() {
        super.onCreate()
        checkpoint("service_created")
        initializationFailure = when {
            Build.VERSION.SDK_INT < 28 -> OpenFluxBrowserFailure.Unsupported
            else -> runCatching {
                if (!dataDirectoryInitialized) {
                    WebView.setDataDirectorySuffix("openflux_verification")
                    dataDirectoryInitialized = true
                }
            }.exceptionOrNull()?.let { OpenFluxBrowserFailure.Failed }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        checkpoint("service_bound")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        active?.let { finish(it, null, OpenFluxBrowserFailure.Failed) }
        return false
    }

    override fun onDestroy() {
        active?.let { finish(it, null, OpenFluxBrowserFailure.Failed) }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun begin(message: Message) {
        checkpoint("request_received")
        val reply = message.replyTo ?: return
        initializationFailure?.let { respond(reply, null, it); return }
        if (active != null || cleaning) {
            respond(reply, null, OpenFluxBrowserFailure.Failed)
            return
        }
        val url = message.data.getString("document_url").orEmpty()
        val network = message.data.getParcelable<Network>("network")
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        if (!isOpenFluxBrowserNavigationAllowed(url)) {
            respond(reply, null, OpenFluxBrowserFailure.Failed)
            return
        }
        if (network == null || capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            respond(reply, null, OpenFluxBrowserFailure.Network)
            return
        }
        val session = Session(url, reply, connectivity.boundNetworkForProcess)
        active = session
        handler.postDelayed(session.timeout, OPENFLUX_BROWSER_TIMEOUT_MS)
        try {
            check(connectivity.bindProcessToNetwork(network))
            checkpoint("network_bound")
            CookieManager.getInstance().removeAllCookies {
                if (active !== session) return@removeAllCookies
                checkpoint("cookies_cleared")
                runCatching {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    createWebView(session)
                }.onFailure { finish(session, null, OpenFluxBrowserFailure.Failed) }
            }
        } catch (_: Exception) {
            finish(session, null, OpenFluxBrowserFailure.Failed)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(session: Session) {
        val view = WebView(this)
        session.view = view
        checkpoint("view_created")
        WebView.setWebContentsDebuggingEnabled(false)
        view.settings.apply {
            // Desktop editor configuration is required; the mobile landing page cannot bootstrap.
            openFluxBrowserDesktopUserAgent(WebSettings.getDefaultUserAgent(this@OpenFluxBrowserVerificationService))
                ?.let { userAgentString = it }
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            saveFormData = false
            savePassword = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, false)
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply {
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    if (active === session && isOpenFluxBrowserResourceAllowed(request.url.toString())) null else blockedResponse()
            })
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = if (request.isForMainFrame) isOpenFluxBrowserNavigationAllowed(request.url.toString())
                    else isOpenFluxBrowserResourceAllowed(request.url.toString())
                if (!allowed && request.isForMainFrame) finish(session, null, OpenFluxBrowserFailure.Failed)
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (active === session && !isOpenFluxBrowserNavigationAllowed(url)) {
                    finish(session, null, OpenFluxBrowserFailure.Failed)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (active === session) {
                    session.navigationFinished = true
                    checkpoint("page_finished")
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val allowed = if (request.isForMainFrame) isOpenFluxBrowserNavigationAllowed(request.url.toString())
                    else isOpenFluxBrowserResourceAllowed(request.url.toString())
                return if (active === session && allowed) null else {
                    session.blockedResources.incrementAndGet()
                    blockedResponse()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(session, null, OpenFluxBrowserFailure.Failed)
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed)
            }

            override fun onReceivedHttpAuthRequest(view: WebView, authHandler: HttpAuthHandler, host: String, realm: String) {
                authHandler.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed)
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                request.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish(session, null, OpenFluxBrowserFailure.Failed)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = rejectDialog(result)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = rejectDialog(result)
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean = rejectDialog(result)
            private fun rejectDialog(result: JsResult): Boolean {
                result.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed)
                return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> finish(session, null, OpenFluxBrowserFailure.Failed) }
        view.loadUrl(session.documentUrl)
        handler.postDelayed(session.poll, 500)
    }

    private fun inspect(session: Session) {
        if (active !== session) return
        val view = session.view ?: return
        if (!isOpenFluxBrowserNavigationAllowed(view.url.orEmpty())) {
            finish(session, null, OpenFluxBrowserFailure.Failed)
            return
        }
        view.evaluateJavascript("($OPENFLUX_BROWSER_BOOTSTRAP_READY_SCRIPT)()") { result ->
            if (active !== session) return@evaluateJavascript
            session.polls++
            if (result == "true" && isOpenFluxBrowserNavigationAllowed(view.url.orEmpty())) {
                try {
                    val cookies = CookieManager.getInstance()
                    val headers = mapOf(
                        "disk.yandex.ru" to cookies.getCookie("https://disk.yandex.ru/").orEmpty(),
                        "docs.yandex.ru" to cookies.getCookie("https://docs.yandex.ru/").orEmpty()
                    )
                    parseOpenFluxBrowserCookieHeaders(headers)
                    finish(session, headers, null)
                } catch (failure: OpenFluxBrowserException) {
                    finish(session, null, failure.reason)
                } catch (_: Exception) {
                    finish(session, null, OpenFluxBrowserFailure.Failed)
                }
            } else {
                view.evaluateJavascript(OPENFLUX_BROWSER_PAGE_STATE_SCRIPT) { checkpoint ->
                    if (active !== session) return@evaluateJavascript
                    session.pageState = openFluxBrowserPageState(checkpoint)
                    handler.postDelayed(session.poll, 500)
                }
            }
        }
    }

    private fun finish(session: Session, headers: Map<String, String>?, failure: OpenFluxBrowserFailure?) {
        if (active !== session) return
        active = null
        cleaning = true
        if (failure != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Fixed categories only: never serialize a URL, page content or cookies.
            Log.i("OpenFluxBrowser", "OPENFLUX_BROWSER_CHECKPOINT view=${session.view != null}" +
                " page=${session.navigationFinished} polls=${session.polls.coerceIn(0, 999)}" +
                " state=${session.pageState.name} blocked=${session.blockedResources.get().coerceIn(0, 999)}")
        }
        handler.removeCallbacks(session.timeout)
        handler.removeCallbacks(session.poll)
        session.view?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.clearHistory(); view.clearCache(true); view.clearFormData() }
            runCatching { view.destroy() }
        }
        session.view = null
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { connectivity.bindProcessToNetwork(session.previousNetwork) }
        // No cookie file or profile field: return the in-memory value only after clearing our store.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().removeAllCookies {
                val cleared = runCatching { CookieManager.getInstance().flush() }.isSuccess
                cleaning = false
                val resultFailure = failure ?: OpenFluxBrowserFailure.Failed.takeUnless { cleared }
                respond(session.reply, headers.takeIf { resultFailure == null }, resultFailure)
            }
        }.onFailure {
            cleaning = false
            respond(session.reply, null, OpenFluxBrowserFailure.Failed)
        }
    }

    private fun respond(reply: Messenger, headers: Map<String, String>?, failure: OpenFluxBrowserFailure?) {
        checkpoint(if (failure == null) "response_success" else "response_failed")
        runCatching {
            reply.send(Message.obtain(null, OPENFLUX_BROWSER_RESULT).apply {
                data = Bundle().apply {
                    if (failure != null) putString("error", failure.name) else {
                        putBundle("cookies", Bundle().apply {
                            headers?.forEach { (host, value) -> putString(host, value) }
                        })
                    }
                }
            })
        }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf())
    )

    private fun checkpoint(stage: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.i("OpenFluxBrowser", "OPENFLUX_BROWSER_STAGE $stage")
        }
    }

    private inner class Session(val documentUrl: String, val reply: Messenger, val previousNetwork: Network?) {
        var view: WebView? = null
        var navigationFinished = false
        var polls = 0
        var pageState = OpenFluxBrowserPageState.Unknown
        val blockedResources = AtomicInteger(0)
        val timeout = Runnable { finish(this, null, OpenFluxBrowserFailure.Timeout) }
        val poll = Runnable { inspect(this) }
    }

    private companion object {
        var dataDirectoryInitialized = false
    }
}
