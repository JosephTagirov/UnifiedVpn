package org.olcbox.app.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
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
        runCatching {
            when (message.what) {
                OPENFLUX_BROWSER_VERIFY -> begin(message)
                OPENFLUX_BROWSER_CANCEL -> active?.takeIf {
                    it.reply.binder == message.replyTo?.binder && it.nonce == message.data.getString("nonce")
                }?.let { finish(it, null, OpenFluxBrowserFailure.Cancelled) }
            }
        }.onFailure { active?.let { finish(it, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request) } }
        true
    })
    @Volatile private var active: Session? = null
    private var cleaning = false
    private var initializationFailure: OpenFluxBrowserFailure? = null
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        active?.let { finish(it, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected) }
        return false
    }

    override fun onDestroy() {
        active?.let { finish(it, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun begin(message: Message) {
        checkpoint("request_received")
        val reply = message.replyTo ?: return
        val identity = message.data
        initializationFailure?.let {
            respond(reply, identity, null, it, diagnosticStage = OpenFluxBrowserDiagnosticStage.BrowserInitialization)
            return
        }
        if (active != null || cleaning) {
            respond(reply, identity, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
            return
        }
        val url = message.data.getString("document_url").orEmpty()
        val nonce = message.data.getString("nonce").orEmpty()
        val profileId = message.data.getString("profile_id").orEmpty()
        val generation = message.data.getLong("generation", -1)
        val network = message.data.getParcelable<Network>("network")
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        if (!isOpenFluxBrowserNavigationAllowed(url) || !Regex("[0-9a-f]{32}").matches(nonce) ||
            profileId.isBlank() || profileId.length > 256 || generation < 0) {
            respond(reply, identity, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
            return
        }
        if (network == null || capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            respond(reply, identity, null, OpenFluxBrowserFailure.Network, diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkBinding)
            return
        }
        val session = Session(url, reply, nonce, profileId, generation, network)
        active = session
        handler.postDelayed(session.timeout, OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS)
        handler.postDelayed(session.checkCurrent, 500)
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                session.networkCallback, handler
            )
            session.networkCallbackRegistered = true
            val pending = PendingIntent.getActivity(this, 0,
                Intent(this, OpenFluxBrowserVerificationActivity::class.java).apply {
                    action = "$packageName.OPENFLUX_VERIFY.$nonce"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra("nonce", nonce)
                    putExtra("profile_id", profileId)
                    putExtra("generation", generation)
                }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            session.pendingIntent = pending
            reply.send(Message.obtain(null, OPENFLUX_BROWSER_PROMPT).apply {
                data = session.identity().apply { putParcelable("pending_intent", pending) }
            })
            checkpoint("user_action_required")
        } catch (_: Exception) {
            finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Prompt)
        }
    }

    private fun attach(activity: OpenFluxBrowserVerificationActivity, nonce: String, profileId: String, generation: Long): Boolean {
        val session = active ?: return false
        if ((getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked != false) return false
        if (session.nonce != nonce || session.profileId != profileId || session.generation != generation ||
            session.claimed || !isCurrent(session)) return false
        session.claimed = true
        session.activity = activity
        session.pendingIntent?.cancel()
        session.pendingIntent = null
        try {
            session.diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkBinding
            session.previousNetwork = connectivity.boundNetworkForProcess
            check(connectivity.bindProcessToNetwork(session.network))
            session.networkBound = true
            checkpoint("network_bound")
            session.diagnosticStage = OpenFluxBrowserDiagnosticStage.BrowserInitialization
            session.storeTouched = true
            CookieManager.getInstance().removeAllCookies {
                if (active !== session) return@removeAllCookies
                checkpoint("cookies_cleared")
                runCatching {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    createWebView(session, activity)
                }.onFailure { finish(session, null, OpenFluxBrowserFailure.Failed) }
            }
        } catch (_: Exception) {
            finish(session, null, OpenFluxBrowserFailure.Failed)
        }
        return true
    }

    private fun isCurrent(session: Session): Boolean = runCatching {
        if (SystemClock.elapsedRealtime() - session.createdAt !in 0L until OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS) return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(session.network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(session: Session, activity: OpenFluxBrowserVerificationActivity) {
        if (!isCurrent(session)) {
            finish(session, null, OpenFluxBrowserFailure.Network, diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkChanged)
            return
        }
        val view = WebView(activity)
        session.view = view
        view.filterTouchesWhenObscured = true
        activity.attachVerificationView(view)
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
        session.userAgent = view.settings.userAgentString.also {
            check(isOpenFluxBrowserUserAgentValid(it))
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
                if (!allowed && request.isForMainFrame) {
                    finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.NavigationRejected)
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // This callback may precede WebView.url; only inspection or completion ends the initial wait.
                if (active === session) {
                    session.diagnosticStage = OpenFluxBrowserDiagnosticStage.Navigation
                    session.httpStatus = null
                    session.navigationGeneration++
                    session.navigationFinished = false
                    session.pageState = OpenFluxBrowserPageState.Loading
                    if (!isOpenFluxBrowserNavigationAllowed(url)) {
                        finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.NavigationRejected)
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (active === session) {
                    session.navigationObserved = true
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
                if (request.isForMainFrame) {
                    finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = openFluxBrowserWebViewFailureStage(error.errorCode))
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (active === session && request.isForMainFrame) {
                    // A challenge may use a non-2xx response; retain the observation without changing navigation behavior.
                    val status = openFluxBrowserHttpStatus(response.statusCode)
                    session.httpStatus = status
                    if (session.reportedHttpStatuses.add(status)) {
                        runCatching {
                            session.reply.send(Message.obtain(null, OPENFLUX_BROWSER_HTTP_OBSERVATION).apply {
                                data = session.identity().apply { putString("http_status", status.code) }
                            })
                        }
                    }
                }
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                val resource = openFluxBrowserTlsResource(error.url)
                val stage = openFluxBrowserTlsFailureStage(error.primaryError)
                checkpoint("tls_${resource.name}_${error.primaryError.coerceIn(0, 5)}")
                if (active === session && canContinueAfterBlockingOpenFluxTlsResource(error.url, view.url)) {
                    // Deny the invalid TLS resource, but let the independently verified document finish.
                    if (session.reportedBlockedTlsResources.add(stage to resource)) {
                        runCatching {
                            session.reply.send(Message.obtain(null, OPENFLUX_BROWSER_TLS_OBSERVATION).apply {
                                data = session.identity().apply {
                                    putString("diagnostic_stage", stage.code)
                                    putString("tls_resource", resource.code)
                                }
                            })
                        }
                    }
                    return
                }
                finish(session, null, OpenFluxBrowserFailure.Failed,
                    diagnosticStage = stage, tlsResource = resource)
            }

            override fun onReceivedHttpAuthRequest(view: WebView, authHandler: HttpAuthHandler, host: String, realm: String) {
                authHandler.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.HttpAuthentication)
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                request.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ClientCertificate)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.RendererGone)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = rejectDialog(result)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = rejectDialog(result)
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean = rejectDialog(result)
            private fun rejectDialog(result: JsResult): Boolean {
                result.cancel()
                finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.JavascriptDialog)
                return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ ->
            finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.DownloadRejected)
        }
        view.loadUrl(session.documentUrl)
        handler.postDelayed(session.poll, 500)
    }

    private fun inspect(session: Session) {
        if (active !== session) return
        val view = session.view ?: return
        when (openFluxBrowserNavigationPoll(view.url, session.navigationObserved)) {
            OpenFluxBrowserNavigationPoll.AwaitInitialNavigation -> {
                // A cold WebView can have no current URL before its first navigation starts.
                handler.postDelayed(session.poll, 500)
                return
            }
            OpenFluxBrowserNavigationPoll.Reject -> {
                finish(session, null, OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.NavigationRejected)
                return
            }
            OpenFluxBrowserNavigationPoll.InspectDocument -> session.navigationObserved = true
        }
        val navigationGeneration = session.navigationGeneration
        val inspectedUrl = view.url
        session.diagnosticStage = OpenFluxBrowserDiagnosticStage.PageInspection
        view.evaluateJavascript("($OPENFLUX_BROWSER_BOOTSTRAP_READY_SCRIPT)()") ready@{ result ->
            if (active !== session) return@ready
            if (session.navigationGeneration != navigationGeneration || view.url != inspectedUrl) {
                handler.postDelayed(session.poll, 500)
                return@ready
            }
            session.polls++
            if (result == "true" && isOpenFluxBrowserNavigationAllowed(view.url.orEmpty())) {
                session.diagnosticStage = OpenFluxBrowserDiagnosticStage.DocumentCapture
                view.evaluateJavascript(OPENFLUX_BROWSER_CLASSIC_DOCUMENT_SCRIPT) capture@{ captured ->
                    if (active !== session) return@capture
                    if (session.navigationGeneration != navigationGeneration || view.url != inspectedUrl) {
                        handler.postDelayed(session.poll, 500)
                        return@capture
                    }
                    try {
                        if (!isCurrent(session) || !isOpenFluxBrowserNavigationAllowed(view.url.orEmpty())) {
                            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Expired)
                        }
                        val classicDocument = openFluxBrowserCapturedDocument(captured, session.documentUrl)
                        session.diagnosticStage = OpenFluxBrowserDiagnosticStage.CookieExtraction
                        val cookies = CookieManager.getInstance()
                        val headers = mapOf(
                            "disk.yandex.ru" to cookies.getCookie("https://disk.yandex.ru/").orEmpty(),
                            "docs.yandex.ru" to cookies.getCookie("https://docs.yandex.ru/").orEmpty()
                        )
                        val parsedCookies = parseOpenFluxBrowserCookieHeaders(headers)
                        // Check the complete native message bound before sending private data across IPC.
                        openFluxBrowserResponse(session.nonce, parsedCookies, session.userAgent, classicDocument)
                        finish(session, headers, null, classicDocument)
                    } catch (failure: OpenFluxBrowserException) {
                        finish(session, null, failure.reason, validationStage = failure.validationStage,
                            diagnosticStage = failure.diagnosticStage, tlsResource = failure.tlsResource)
                    } catch (_: Exception) {
                        finish(session, null, OpenFluxBrowserFailure.Failed)
                    }
                }
            } else {
                view.evaluateJavascript(OPENFLUX_BROWSER_PAGE_STATE_SCRIPT) checkpoint@{ checkpoint ->
                    if (active !== session) return@checkpoint
                    if (session.navigationGeneration != navigationGeneration || view.url != inspectedUrl) {
                        handler.postDelayed(session.poll, 500)
                        return@checkpoint
                    }
                    session.pageState = openFluxBrowserPageState(checkpoint)
                    handler.postDelayed(session.poll, 500)
                }
            }
        }
    }

    private fun finish(
        session: Session,
        headers: Map<String, String>?,
        failure: OpenFluxBrowserFailure?,
        classicDocument: OpenFluxClassicDocument? = null,
        validationStage: OpenFluxBrowserValidationStage? = null,
        diagnosticStage: OpenFluxBrowserDiagnosticStage? = null,
        tlsResource: OpenFluxBrowserTlsResource? = null
    ) {
        if (active !== session) return
        active = null
        cleaning = true
        val failedStage = diagnosticStage ?: session.diagnosticStage
        if (failure != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Fixed categories only: never serialize a URL, page content or cookies.
            Log.i("OpenFluxBrowser", "OPENFLUX_BROWSER_CHECKPOINT view=${session.view != null}" +
                " page=${session.navigationFinished} polls=${session.polls.coerceIn(0, 999)}" +
                " state=${session.pageState.name} blocked=${session.blockedResources.get().coerceIn(0, 999)}")
        }
        handler.removeCallbacks(session.timeout)
        handler.removeCallbacks(session.poll)
        handler.removeCallbacks(session.checkCurrent)
        session.pendingIntent?.cancel()
        session.pendingIntent = null
        if (session.networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(session.networkCallback) }
        session.activity?.closeVerification()
        session.activity = null
        session.view?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.clearHistory(); view.clearCache(true); view.clearFormData() }
            runCatching { view.destroy() }
        }
        session.view = null
        if (session.networkBound) runCatching { connectivity.bindProcessToNetwork(session.previousNetwork) }
        if (!session.storeTouched) {
            cleaning = false
            respond(session.reply, session.identity(), null, failure ?: OpenFluxBrowserFailure.Failed,
                validationStage = validationStage, diagnosticStage = failedStage, httpStatus = session.httpStatus,
                tlsResource = tlsResource)
            return
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        // No cookie file or profile field: return the in-memory value only after clearing our store.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().removeAllCookies {
                val cleared = runCatching { CookieManager.getInstance().flush() }.isSuccess
                cleaning = false
                val resultFailure = failure ?: OpenFluxBrowserFailure.Failed.takeUnless { cleared }
                respond(session.reply, session.identity(), headers.takeIf { resultFailure == null }, resultFailure,
                    session.userAgent, classicDocument.takeIf { resultFailure == null }, validationStage,
                    if (failure != null) failedStage else OpenFluxBrowserDiagnosticStage.CookieCleanup.takeIf { resultFailure != null },
                    session.httpStatus, tlsResource)
            }
        }.onFailure {
            cleaning = false
            respond(session.reply, session.identity(), null, failure ?: OpenFluxBrowserFailure.Failed,
                validationStage = validationStage,
                diagnosticStage = if (failure != null) failedStage else OpenFluxBrowserDiagnosticStage.CookieCleanup,
                httpStatus = session.httpStatus, tlsResource = tlsResource)
        }
    }

    private fun respond(
        reply: Messenger,
        identity: Bundle,
        headers: Map<String, String>?,
        failure: OpenFluxBrowserFailure?,
        userAgent: String? = null,
        classicDocument: OpenFluxClassicDocument? = null,
        validationStage: OpenFluxBrowserValidationStage? = null,
        diagnosticStage: OpenFluxBrowserDiagnosticStage? = null,
        httpStatus: OpenFluxBrowserHttpStatus? = null,
        tlsResource: OpenFluxBrowserTlsResource? = null
    ) {
        checkpoint(if (failure == null) "response_success" else "response_failed")
        runCatching {
            reply.send(Message.obtain(null, OPENFLUX_BROWSER_RESULT).apply {
                data = Bundle().apply {
                    putString("nonce", identity.getString("nonce"))
                    putString("profile_id", identity.getString("profile_id"))
                    putLong("generation", identity.getLong("generation", -1))
                    if (failure != null) {
                        putString("error", failure.name)
                        validationStage?.let { putString("validation_stage", it.code) }
                        diagnosticStage?.let { putString("diagnostic_stage", it.code) }
                        httpStatus?.let { putString("http_status", it.code) }
                        tlsResource?.let { putString("tls_resource", it.code) }
                    } else {
                        check(userAgent != null && isOpenFluxBrowserUserAgentValid(userAgent))
                        putString("user_agent", userAgent)
                        classicDocument?.let { putString("classic_document", it.json.toString()) }
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

    private inner class Session(
        val documentUrl: String,
        val reply: Messenger,
        val nonce: String,
        val profileId: String,
        val generation: Long,
        val network: Network
    ) {
        val createdAt = SystemClock.elapsedRealtime()
        var pendingIntent: PendingIntent? = null
        var activity: OpenFluxBrowserVerificationActivity? = null
        var claimed = false
        var storeTouched = false
        var networkBound = false
        var previousNetwork: Network? = null
        var networkCallbackRegistered = false
        var view: WebView? = null
        var userAgent: String? = null
        var navigationObserved = false
        var navigationGeneration = 0L
        var navigationFinished = false
        var diagnosticStage = OpenFluxBrowserDiagnosticStage.AwaitingUser
        var httpStatus: OpenFluxBrowserHttpStatus? = null
        val reportedHttpStatuses = mutableSetOf<OpenFluxBrowserHttpStatus>()
        val reportedBlockedTlsResources = mutableSetOf<Pair<OpenFluxBrowserDiagnosticStage, OpenFluxBrowserTlsResource>>()
        var polls = 0
        var pageState = OpenFluxBrowserPageState.Unknown
        val blockedResources = AtomicInteger(0)
        val timeout = Runnable { finish(this, null, openFluxBrowserTimeoutFailure(pageState, navigationFinished)) }
        val poll = Runnable { inspect(this) }
        val checkCurrent: Runnable = object : Runnable {
            override fun run() {
                if (active !== this@Session) return
                if (!isCurrent(this@Session)) finish(this@Session, null, OpenFluxBrowserFailure.Network,
                    diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkChanged)
                else handler.postDelayed(this, 500)
            }
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == this@Session.network) finish(this@Session, null, OpenFluxBrowserFailure.Network,
                    diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkChanged)
            }
        }
        fun identity() = Bundle().apply {
            putString("nonce", nonce)
            putString("profile_id", profileId)
            putLong("generation", generation)
        }
    }

    companion object {
        private var dataDirectoryInitialized = false
        private var instance: OpenFluxBrowserVerificationService? = null

        internal fun attachActivity(activity: OpenFluxBrowserVerificationActivity, nonce: String, profileId: String, generation: Long): Boolean =
            instance?.attach(activity, nonce, profileId, generation) == true

        internal fun cancelActivity(
            activity: OpenFluxBrowserVerificationActivity,
            diagnosticStage: OpenFluxBrowserDiagnosticStage = OpenFluxBrowserDiagnosticStage.UserCancelled
        ) {
            val service = instance ?: return
            service.active?.takeIf { it.activity === activity }?.let {
                service.finish(it, null, OpenFluxBrowserFailure.Cancelled, diagnosticStage = diagnosticStage)
            }
        }

        internal fun cancelUnclaimedActivity(
            activity: OpenFluxBrowserVerificationActivity,
            diagnosticStage: OpenFluxBrowserDiagnosticStage = OpenFluxBrowserDiagnosticStage.UserCancelled
        ) {
            val service = instance ?: return
            val session = service.active ?: return
            if (!session.claimed && session.nonce == activity.intent.getStringExtra("nonce") &&
                session.profileId == activity.intent.getStringExtra("profile_id") &&
                session.generation == activity.intent.getLongExtra("generation", -1)
            ) service.finish(session, null, OpenFluxBrowserFailure.Cancelled, diagnosticStage = diagnosticStage)
        }
    }
}
