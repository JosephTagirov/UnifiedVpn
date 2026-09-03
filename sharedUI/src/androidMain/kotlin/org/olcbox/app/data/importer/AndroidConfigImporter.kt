package org.olcbox.app.data.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import org.olcbox.app.ui.localization.androidUiText
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidConfigImporter(private val context: Context) : ConfigImporter {
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
            clipboard.setPrimaryClip(clip)
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
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        ClipboardPayloadCodec.decodeOrOriginal(reader.readText())
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}
