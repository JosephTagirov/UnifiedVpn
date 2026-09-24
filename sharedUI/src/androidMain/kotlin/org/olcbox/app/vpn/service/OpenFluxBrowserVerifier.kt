package org.olcbox.app.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OpenFluxBrowserVerifier(context: Context) {
    private val context = context.applicationContext

    suspend fun verify(documentUrl: String): List<OpenFluxBrowserCookie> {
        if (Build.VERSION.SDK_INT < 28) throw OpenFluxBrowserException(OpenFluxBrowserFailure.Unsupported)
        if (openFluxBrowserDocumentHost(documentUrl) == null) {
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.boundNetworkForProcess
            ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Network)
        return withTimeoutOrNull(OPENFLUX_BROWSER_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<List<OpenFluxBrowserCookie>> { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    var bound = false
                    var completed = false
                    var remote: Messenger? = null
                    lateinit var connection: ServiceConnection
                    lateinit var reply: Messenger
                    fun cleanup() {
                        runCatching {
                            remote?.send(Message.obtain(null, OPENFLUX_BROWSER_CANCEL).apply { replyTo = reply })
                        }
                        if (bound) {
                            bound = false
                            runCatching { context.unbindService(connection) }
                        }
                        remote = null
                    }
                    fun fail(reason: OpenFluxBrowserFailure) {
                        if (completed) return
                        completed = true
                        cleanup()
                        if (continuation.isActive) continuation.resumeWithException(OpenFluxBrowserException(reason))
                    }
                    reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.what != OPENFLUX_BROWSER_RESULT || completed) return@Handler true
                        val error = message.data.getString("error")
                        val headers = message.data.getBundle("cookies")
                        val cookies = runCatching {
                            check(headers != null)
                            parseOpenFluxBrowserCookieHeaders(headers.keySet().associateWith { headers.getString(it).orEmpty() })
                        }.getOrNull()
                        if (error != null || cookies == null) {
                            fail(OpenFluxBrowserFailure.entries.firstOrNull { it.name == error } ?: OpenFluxBrowserFailure.Failed)
                        } else {
                            completed = true
                            cleanup()
                            if (continuation.isActive) continuation.resume(cookies)
                        }
                        true
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
                                    putParcelable("network", network)
                                }
                            }
                            if (runCatching { remote?.send(request) }.isFailure) fail(OpenFluxBrowserFailure.Failed)
                        }

                        override fun onServiceDisconnected(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed)
                        override fun onBindingDied(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed)
                        override fun onNullBinding(name: ComponentName) = fail(OpenFluxBrowserFailure.Failed)
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
                    if (!bound) fail(OpenFluxBrowserFailure.Failed)
                }
            }
        } ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Timeout)
    }
}

internal const val OPENFLUX_BROWSER_VERIFY = 1
internal const val OPENFLUX_BROWSER_CANCEL = 2
internal const val OPENFLUX_BROWSER_RESULT = 3
