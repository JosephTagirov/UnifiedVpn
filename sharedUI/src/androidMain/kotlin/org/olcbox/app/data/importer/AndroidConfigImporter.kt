package org.olcbox.app.data.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import org.olcbox.app.ui.localization.androidUiText
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class AndroidConfigImporter(context: Context) : ConfigImporter {
    private val context = context.applicationContext
    private val clipboardClearGeneration = AtomicLong(0L)

    override fun getFromClipboard(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = runCatching {
                clip.getItemAt(0).coerceToText(context)?.toString()
                    ?.let(ClipboardPayloadCodec::decodeOrOriginal)
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    context.androidUiText("Clipboard is empty or invalid"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return text
        }
        Toast.makeText(
            context,
            context.androidUiText("No clipboard data found"),
            Toast.LENGTH_SHORT
        ).show()
        return null
    }

    override fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val payload = ClipboardPayloadCodec.encode(text)
            val clip = ClipData.newPlainText("Unified VPN locations", payload)
            markClipboardContentSensitive(clip)
            clipboard.setPrimaryClip(clip)
            scheduleClipboardClear(clipboard, clipboardDigest(payload))
        }.onSuccess {
            Toast.makeText(
                context,
                context.androidUiText("Config copied to clipboard"),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(
                context,
                context.androidUiText("Config is too large for the clipboard"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override suspend fun readTextFromSource(source: Any): String? {
        if (source is Uri) {
            return try {
                context.contentResolver.openInputStream(source)?.use { inputStream ->
                    ClipboardPayloadCodec.decodeOrOriginal(inputStream.readBoundedUtf8())
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun markClipboardContentSensitive(clip: ClipData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        clip.description.extras = PersistableBundle().apply {
            putBoolean(CLIPBOARD_IS_SENSITIVE_EXTRA, true)
        }
    }

    private fun scheduleClipboardClear(
        clipboard: ClipboardManager,
        expectedDigest: ByteArray
    ) {
        val generation = clipboardClearGeneration.incrementAndGet()
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (clipboardClearGeneration.get() != generation) return@postDelayed
                val currentText = runCatching {
                    clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                }.getOrNull() ?: return@postDelayed
                if (!MessageDigest.isEqual(expectedDigest, clipboardDigest(currentText))) {
                    return@postDelayed
                }

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            },
            CLIPBOARD_CLEAR_DELAY_MS
        )
    }

    private fun clipboardDigest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())

    private companion object {
        private const val CLIPBOARD_IS_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
        private const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L
    }
}
