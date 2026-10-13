package org.olcbox.app.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

internal class OpenFluxBrowserVerifier(context: Context, private val log: (String) -> Unit = {}) {
    private val context = context.applicationContext

    suspend fun prepare(documentUrl: String, requestContext: OpenFluxVerificationContext): OpenFluxBrowserLease {
        if (Build.VERSION.SDK_INT < 28) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Unsupported)
        if (openFluxBrowserDocumentHost(documentUrl) == null) {
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.boundNetworkForProcess
            ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Network, diagnosticStage = OpenFluxBrowserDiagnosticStage.NetworkBinding)
        fun requestIsCurrent(): Boolean = runCatching {
            val capabilities = connectivity.getNetworkCapabilities(network)
            requestContext.isCurrent() && connectivity.boundNetworkForProcess == network &&
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrDefault(false)
        if (!requestIsCurrent()) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Expired)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val session = withTimeoutOrNull(OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<OpenFluxBrowserSession> { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    var bound = false
                    var completed = false
                    var remote: Messenger? = null
                    lateinit var connection: ServiceConnection
                    lateinit var reply: Messenger
                    lateinit var checkCurrent: Runnable
                    fun cleanup() {
                        handler.removeCallbacks(checkCurrent)
                        runCatching {
                            remote?.send(Message.obtain(null, OPENFLUX_BROWSER_CANCEL).apply {
                                replyTo = reply
                                data = Bundle().apply { putString("nonce", nonce) }
                            })
                        }
                        if (bound) {
                            bound = false
                            runCatching { context.unbindService(connection) }
                        }
                        remote = null
                        runCatching { requestContext.onPrompt(null) }
                    }
                    fun fail(
                        reason: OpenFluxBrowserFailure,
                        validationStage: OpenFluxBrowserValidationStage? = null,
                        diagnosticStage: OpenFluxBrowserDiagnosticStage? = null,
                        httpStatus: OpenFluxBrowserHttpStatus? = null,
                        tlsResource: OpenFluxBrowserTlsResource? = null
                    ) {
                        if (completed) return
                        completed = true
                        cleanup()
                        if (continuation.isActive) continuation.resumeWithException(OpenFluxBrowserException(reason, validationStage, diagnosticStage, httpStatus, tlsResource))
                    }
                    checkCurrent = object : Runnable {
                        override fun run() {
                            if (completed) return
                            if (!requestIsCurrent()) fail(OpenFluxBrowserFailure.Expired, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
                            else handler.postDelayed(this, 250)
                        }
                    }
                    reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (completed || message.sendingUid != Process.myUid()) return@Handler true
                        try {
                        if (message.data.getString("nonce") != nonce ||
                            message.data.getString("profile_id") != requestContext.profileStorageId ||
                            message.data.getLong("generation", -1) != requestContext.generation || !requestIsCurrent()
                        ) {
                            fail(OpenFluxBrowserFailure.Expired, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
                            return@Handler true
                        }
                        if (message.what == OPENFLUX_BROWSER_PROMPT) {
                            @Suppress("DEPRECATION")
                            val pendingIntent = message.data.getParcelable<PendingIntent>("pending_intent")
                            if (pendingIntent?.creatorUid != Process.myUid() || pendingIntent.creatorPackage != context.packageName) {
                                fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Prompt)
                            } else if (runCatching {
                                requestContext.onPrompt(OpenFluxVerificationPrompt(
                                    nonce, requestContext.profileStorageId, requestContext.generation, pendingIntent
                                ))
                            }.isFailure) {
                                fail(OpenFluxBrowserFailure.Required, diagnosticStage = OpenFluxBrowserDiagnosticStage.Prompt)
                            }
                            return@Handler true
                        }
                        if (message.what == OPENFLUX_BROWSER_HTTP_OBSERVATION) {
                            openFluxBrowserHttpStatus(message.data.getString("http_status"))?.let {
                                runCatching { log(openFluxBrowserHttpLogLine(it)) }
                            }
                            return@Handler true
                        }
                        if (message.what == OPENFLUX_BROWSER_TLS_OBSERVATION) {
                            openFluxBrowserBlockedTlsLogLine(message.data.getString("diagnostic_stage"),
                                message.data.getString("tls_resource"))?.let {
                                runCatching { log(it) }
                            }
                            return@Handler true
                        }
                        if (message.what != OPENFLUX_BROWSER_RESULT) return@Handler true
                        val error = message.data.getString("error")
                        val headers = message.data.getBundle("cookies")
                        val sessionResult = runCatching {
                            check(headers != null)
                            val classicDocument = if (message.data.containsKey("classic_document")) {
                                OpenFluxClassicDocument.parse(message.data.getString("classic_document") ?: error("Invalid document"), documentUrl)
                            } else null
                            OpenFluxBrowserSession(
                                parseOpenFluxBrowserCookieHeaders(headers.keySet().associateWith { headers.getString(it).orEmpty() }),
                                message.data.getString("user_agent").orEmpty(),
                                classicDocument
                            )
                        }
                        val session = sessionResult.getOrNull()
                        if (error != null || session == null) {
                            val failure = openFluxBrowserReplyFailure(
                                error,
                                message.data.getString("validation_stage"),
                                message.data.getString("diagnostic_stage"),
                                message.data.getString("http_status"),
                                sessionResult.exceptionOrNull(),
                                message.data.getString("tls_resource")
                            )
                            fail(failure.reason, failure.validationStage, failure.diagnosticStage, failure.httpStatus, failure.tlsResource)
                        } else {
                            completed = true
                            cleanup()
                            if (continuation.isActive) continuation.resume(session)
                        }
                        true
                        } catch (_: Exception) {
                            fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ReplyValidation)
                            true
                        }
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (completed || !continuation.isActive) {
                                cleanup()
                                return
                            }
                            remote = Messenger(binder)
                            val request = Message.obtain(null, OPENFLUX_BROWSER_VERIFY).apply {
                                replyTo = reply
                                data = Bundle().apply {
                                    putString("document_url", documentUrl)
                                    putString("nonce", nonce)
                                    putString("profile_id", requestContext.profileStorageId)
                                    putLong("generation", requestContext.generation)
                                    putParcelable("network", network)
                                }
                            }
                            if (runCatching { remote?.send(request) }.isFailure) {
                                fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.Request)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected)
                        override fun onBindingDied(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected)
                        override fun onNullBinding(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected)
                    }
                    continuation.invokeOnCancellation {
                        handler.post {
                            completed = true
                            cleanup()
                        }
                    }
                    bound = runCatching {
                        context.bindService(
                            Intent(context, OpenFluxBrowserVerificationService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE
                        )
                    }.getOrDefault(false)
                    if (!bound) fail(OpenFluxBrowserFailure.Failed, diagnosticStage = OpenFluxBrowserDiagnosticStage.ServiceDisconnected)
                    else handler.postDelayed(checkCurrent, 250)
                }
            }
        } ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Timeout)
        return OpenFluxBrowserLease(session, documentUrl, ::requestIsCurrent).also { it.requireCurrent() }
    }
}

internal const val OPENFLUX_BROWSER_VERIFY = 1
internal const val OPENFLUX_BROWSER_CANCEL = 2
internal const val OPENFLUX_BROWSER_RESULT = 3
internal const val OPENFLUX_BROWSER_PROMPT = 4
internal const val OPENFLUX_BROWSER_HTTP_OBSERVATION = 5
internal const val OPENFLUX_BROWSER_TLS_OBSERVATION = 6
