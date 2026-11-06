package com.example.teethree.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

class AndroidContextClipboard(context: Context) {
    private val clipboard =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun read(): ContextClipboardPayload? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount != 1) return null
        val item = clip.getItemAt(0)
        return ContextClipboardPayload(item.text?.toString().orEmpty(), html = item.htmlText)
    }

    fun write(
        text: String,
        source: ClipboardSource,
        context: kotlinx.serialization.json.JsonObject?,
    ): ContextClipboardPayload {
        return write(encodeContextClipboard(text, source, context))
    }

    fun write(payload: ContextClipboardPayload): ContextClipboardPayload {
        val decoded =
            requireNotNull(decodeContextClipboard(payload)) {
                "This context selection could not be copied."
            }
        val portable =
            encodeContextClipboard(payload.text, decoded.source, contextOf(decoded.records))
        val clip =
            if (decoded.records.isEmpty()) ClipData.newPlainText("T3 Code", portable.text)
            else ClipData.newHtmlText("T3 Code", portable.text, portable.html)
        clipboard.setPrimaryClip(clip)
        return portable
    }
}
