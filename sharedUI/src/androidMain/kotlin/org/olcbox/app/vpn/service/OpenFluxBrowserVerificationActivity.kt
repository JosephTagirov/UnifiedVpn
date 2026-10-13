package org.olcbox.app.vpn.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.olcbox.app.ui.localization.androidUiText

/** Opened only by the explicit, one-shot verification action issued for the active connection. */
class OpenFluxBrowserVerificationActivity : Activity() {
    private lateinit var browserContainer: FrameLayout
    private var attached = false
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { createContent() }.onFailure { cancelVerification(OpenFluxBrowserDiagnosticStage.ActivityInitialization) }
    }

    private fun createContent() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            filterTouchesWhenObscured = true
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        header.addView(TextView(this).apply {
            text = androidUiText("Yandex verification")
            textSize = 18f
            setTextColor(Color.rgb(32, 32, 32))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(Color.rgb(32, 32, 32))
            contentDescription = androidUiText("Cancel verification")
            if (Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
            setBackgroundColor(Color.TRANSPARENT)
            filterTouchesWhenObscured = true
            setOnClickListener { cancelVerification() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        browserContainer = FrameLayout(this).apply { filterTouchesWhenObscured = true }
        root.addView(browserContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (runCatching { (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked != false }.getOrDefault(true)) {
            cancelVerification(OpenFluxBrowserDiagnosticStage.DeviceLocked)
            return
        }
        if (closing || attached) return
        attached = runCatching {
            OpenFluxBrowserVerificationService.attachActivity(
                this, intent.getStringExtra("nonce").orEmpty(), intent.getStringExtra("profile_id").orEmpty(),
                intent.getLongExtra("generation", -1)
            )
        }.getOrDefault(false)
        if (!attached) closeVerification()
    }

    internal fun attachVerificationView(view: WebView) {
        browserContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun closeVerification() {
        closing = true
        if (::browserContainer.isInitialized) browserContainer.removeAllViews()
        finish()
    }

    private fun cancelVerification(diagnosticStage: OpenFluxBrowserDiagnosticStage = OpenFluxBrowserDiagnosticStage.UserCancelled) {
        runCatching {
            if (attached) OpenFluxBrowserVerificationService.cancelActivity(this, diagnosticStage)
            else OpenFluxBrowserVerificationService.cancelUnclaimedActivity(this, diagnosticStage)
        }
        closeVerification()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = cancelVerification()

    override fun onStop() {
        if (!closing) cancelVerification(OpenFluxBrowserDiagnosticStage.ActivityStopped)
        super.onStop()
    }

    override fun onDestroy() {
        if (!closing) runCatching { OpenFluxBrowserVerificationService.cancelActivity(this, OpenFluxBrowserDiagnosticStage.ActivityDestroyed) }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
            Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        return if (obscured) true else super.dispatchTouchEvent(event)
    }
}
