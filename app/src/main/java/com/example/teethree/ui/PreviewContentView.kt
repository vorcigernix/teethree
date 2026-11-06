package com.example.teethree.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val PREVIEW_DOCUMENT_ORIGIN = "https://native-preview.t3.invalid"

internal class PreviewContentView(context: Context) : WebView(context) {
    private var channel: WebMessagePort? = null
    private var document: String? = null
    private var active = true
    private var rendererGone = false
    var receive: (String) -> Unit = {}
    var allowRequest: (Uri) -> Boolean = { false }
    var bridgeEnabled = true
        set(value) {
            field = value
            if (!value) {
                channel?.close()
                channel = null
            }
        }

    var onNavigation: (Uri, Boolean) -> Unit = { _, _ -> }

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.safeBrowsingEnabled = true
        webChromeClient =
            object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
    }

    fun show(html: String, name: String) {
        if (rendererGone) return
        if (document == html) return
        document = html
        channel?.close()
        channel = null
        webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    rendererGone = true
                    channel?.close()
                    channel = null
                    receive("{\"type\":\"renderProcessGone\"}")
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    onNavigation(request.url, request.hasGesture())
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme in setOf("data", "blob", "about") || allowRequest(url))
                        return null
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!bridgeEnabled) return
                    if (
                        Uri.parse(url).let {
                            it.scheme != "https" ||
                                it.host != "native-preview.t3.invalid" ||
                                it.path != "/$name/"
                        } || channel != null
                    )
                        return
                    val ports = createWebMessageChannel()
                    channel = ports[0]
                    ports[0].setWebMessageCallback(
                        object : WebMessagePort.WebMessageCallback() {
                            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                                message.data
                                    ?.takeIf {
                                        it.length <= 262144 && it.toByteArray().size <= 262144
                                    }
                                    ?.let(receive)
                            }
                        }
                    )
                    postWebMessage(
                        WebMessage(name, arrayOf(ports[1])),
                        Uri.parse(PREVIEW_DOCUMENT_ORIGIN),
                    )
                    if (!active) pauseContent()
                }
            }
        loadDataWithBaseURL("$PREVIEW_DOCUMENT_ORIGIN/$name/", html, "text/html", "UTF-8", null)
    }

    fun command(value: JsonObject) {
        if (!rendererGone)
            evaluateJavascript("window.__t3Command&&window.__t3Command($value);void 0", null)
    }

    fun postMessage(value: JsonObject) {
        channel?.postMessage(WebMessage(value.toString()))
    }

    fun pauseContent() {
        active = false
        if (!rendererGone) {
            evaluateJavascript("window.__t3Pause&&window.__t3Pause();void 0", null)
            onPause()
        }
    }

    fun resumeContent() {
        active = true
        if (!rendererGone) {
            onResume()
            evaluateJavascript("window.__t3Resume&&window.__t3Resume();void 0", null)
        }
    }

    fun capture(result: (Bitmap?, String?) -> Unit) {
        val activity =
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
        if (activity == null || width <= 0 || height <= 0 || width.toLong() * height > 8_000_000) {
            result(null, "The preview is not visible.")
            return
        }
        val location = IntArray(2)
        getLocationInWindow(location)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            activity.window,
            Rect(location[0], location[1], location[0] + width, location[1] + height),
            bitmap,
            { code ->
                if (code == PixelCopy.SUCCESS) result(bitmap, null)
                else {
                    bitmap.recycle()
                    result(null, "Could not capture the visible preview.")
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun release() {
        if (!rendererGone) {
            evaluateJavascript("window.__t3Dispose&&window.__t3Dispose();void 0", null)
            pauseContent()
        }
        channel?.close()
        channel = null
        receive = {}
        allowRequest = { false }
        if (!rendererGone) {
            stopLoading()
            loadUrl("about:blank")
        }
        destroy()
    }
}

@Composable
internal fun PreviewContent(
    html: String,
    channelName: String,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit,
    allowRequest: (Uri) -> Boolean,
    onView: (PreviewContentView?) -> Unit,
    connectNativeBridge: Boolean = true,
    onNavigation: (Uri, Boolean) -> Unit = { _, _ -> },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestMessage = rememberUpdatedState(onMessage)
    val latestRequest = rememberUpdatedState(allowRequest)
    val latestNavigation = rememberUpdatedState(onNavigation)
    val reference = remember { arrayOfNulls<PreviewContentView>(1) }
    key(html) {
        AndroidView(
            factory = { context ->
                PreviewContentView(context).also {
                    reference[0] = it
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        it.pauseContent()
                    onView(it)
                }
            },
            modifier = modifier,
            update = { view ->
                view.receive = { latestMessage.value(it) }
                view.allowRequest = { latestRequest.value(it) }
                view.bridgeEnabled = connectNativeBridge
                view.onNavigation = { uri, gesture -> latestNavigation.value(uri, gesture) }
                view.show(html, channelName)
            },
            onRelease = { view ->
                if (reference[0] === view) {
                    reference[0] = null
                    onView(null)
                }
                view.release()
            },
        )
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> reference[0]?.pauseContent()
                Lifecycle.Event.ON_RESUME -> reference[0]?.resumeContent()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

internal fun nativePreviewBridge(name: String) =
    """
(() => {
 let port=null;const pending=[];const name=${JsonPrimitive(name)};
 window.T3NativePreview={postMessage:m=>{if(typeof m!=='string'||m.length>262144)return;if(port)port.postMessage(m);else if(pending.length<32)pending.push(m);}};
 window.addEventListener('message',e=>{if(port||e.data!==name||e.ports.length!==1)return;port=e.ports[0];port.onmessage=e=>{try{window.__t3Receive&&window.__t3Receive(JSON.parse(e.data));}catch{}};for(const m of pending)port.postMessage(m);pending.length=0;});
})();
"""
        .trimIndent()

internal fun streamPreviewDocument(
    script: String,
    configuration: JsonObject,
    device: Boolean,
    name: String,
): String {
    val global = if (device) "T3DeviceStream" else "T3PreviewStream"
    val config = configuration.toString().replace("<", "\\u003c")
    val access = configuration["access"] as JsonObject
    val base = URI((access["httpBase"] as JsonPrimitive).content)
    val origin = "${base.scheme}://${base.rawAuthority}"
    val wsOrigin = origin.replaceFirst("http", "ws")
    val policy =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob: $origin; media-src blob: $origin; connect-src $origin $wsOrigin; worker-src blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    return """<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="$policy"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>html,body{height:100%;margin:0;overflow:hidden}body>div{position:fixed;inset:0;overflow:hidden}canvas,video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}canvas{touch-action:none;user-select:none}video{pointer-events:none}textarea{position:fixed;left:0;top:0;width:1px;height:1px;margin:-1px;clip:rect(0,0,0,0);font-size:16px}.agent-cursor{position:absolute;width:20px;height:20px;pointer-events:none}</style></head><body><script>
${nativePreviewBridge(name)}
${script.replace("</script", "<\\/script", ignoreCase = true)}
window.__t3Command=v=>$global.command(${if (device) "v.button" else "v"});window.__t3Pause=()=>$global.stop();window.__t3Dispose=()=>$global.stop();window.__t3Resume=()=>$global.start($config);
window.__t3Resume();
</script></body></html>"""
}
