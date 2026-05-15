// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Shapes posted by the mobile renderer web bundle use `attachments` (array). Older bundles may send
 * a single `attachment` with object `data` (mirrors iOS `MobileRendererView` normalization).
 */
private fun svpClientEventAttachments(json: JSONObject): List<Map<String, Any?>> {
    val arr = json.optJSONArray("attachments")
    if (arr != null) {
        val out = mutableListOf<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(jsonObjectToMap(obj))
        }
        return out
    }
    val attachment = json.optJSONObject("attachment") ?: return emptyList()
    val data = attachment.optJSONObject("data") ?: return emptyList()
    return listOf(
        mapOf(
            "type" to "custom",
            "data" to jsonObjectToMap(data)
        )
    )
}

internal interface MobileRendererDelegate {
    fun onSVPClientEvent(text: String, attachments: List<Map<String, Any?>>)
    fun onMobileRendererError(error: Throwable)
    fun onLinkClick(url: Uri)
}

internal class MobileRendererView(
    context: Context,
    private val agentConfig: AgentConfig,
    private val options: AgentVoiceControllerOptions,
    private val conversationEventListener: ConversationEventListener?,
    private val delegate: MobileRendererDelegate
) : FrameLayout(context) {
    private val webView: WebView = WebView(context)
    private var isReady = false
    private val pendingBatches = mutableListOf<String>()

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupWebView()
        loadRendererPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Keep renderer content hidden until the JS bridge reports it's ready, so
        // users do not see transient web "Loading..." states.
        webView.alpha = 0f
        webView.setBackgroundColor(options.voiceStyle.backgroundColor)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = generateVoiceUserAgent(context, isWebView = true)
        webView.settings.applySierraSecurityDefaults()
        webView.webViewClient = MobileRendererWebViewClient(agentConfig, conversationEventListener, delegate)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        else -> "INFO"
                    }
                    Log.d(
                        VOICE_TAG,
                        "MobileRenderer JS [$level]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                    )
                }
                return true
            }
        }
        webView.addJavascriptInterface(RendererBridge(), "AndroidSDK")
        if (agentConfig.apiHost == AgentAPIHost.LOCAL) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun loadRendererPage() {
        val builder = Uri.parse(agentConfig.conversationRendererURL).buildUpon()
        if (!agentConfig.target.isNullOrEmpty()) {
            builder.appendQueryParameter("target", agentConfig.target)
        }
        val bgColor = options.voiceStyle.rendererBackgroundColor ?: options.voiceStyle.backgroundColor
        builder.appendQueryParameter("backgroundColor", bgColor.toHexColor())
        builder.appendQueryParameter("supportsLinkClick", "true")
        webView.loadUrl(builder.build().toString())
    }

    fun pushAttachments(attachments: List<Map<String, Any?>>) {
        val json = JSONArray(attachments).toString()
        if (isReady) {
            evaluatePushAttachments(json)
        } else {
            pendingBatches.add(json)
        }
    }

    fun clearConversation() {
        pendingBatches.clear()
        if (isReady) {
            webView.evaluateJavascript(
                "if (window.sierraMobile?.clearConversation) { window.sierraMobile.clearConversation(); }",
                null
            )
        }
    }

    private fun evaluatePushAttachments(json: String) {
        val escaped = JSONObject.quote(json)
        val js =
            "if (window.sierraMobile?.pushAttachments) { window.sierraMobile.pushAttachments($escaped); }"
        webView.evaluateJavascript(js, null)
    }

    private fun flushPending() {
        val pending = pendingBatches.toList()
        pendingBatches.clear()
        pending.forEach { evaluatePushAttachments(it) }
    }

    fun destroy() {
        webView.stopLoading()
        webView.removeJavascriptInterface("AndroidSDK")
        removeView(webView)
        webView.destroy()
    }

    private inner class RendererBridge {
        @JavascriptInterface
        fun onOpen() {
            Handler(Looper.getMainLooper()).post {
                isReady = true
                webView.alpha = 1f
                flushPending()
            }
        }

        @JavascriptInterface
        fun onSVPClientEvent(dataJSONStr: String) {
            try {
                val json = JSONObject(dataJSONStr)
                val text = json.optString("text", "")
                val attachments = svpClientEventAttachments(json)
                if (text.isEmpty() && attachments.isEmpty()) {
                    return
                }
                delegate.onSVPClientEvent(text, attachments)
            } catch (e: JSONException) {
                delegate.onMobileRendererError(e)
            }
        }

        @JavascriptInterface
        fun onError(reason: String?) {
            val message = reason ?: "unknown-renderer-error"
            delegate.onMobileRendererError(IllegalStateException(message))
        }

        @JavascriptInterface
        fun onLinkClick(url: String?) {
            val raw = url ?: return
            val parsed = try {
                Uri.parse(raw)
            } catch (_: Throwable) {
                return
            }
            Handler(Looper.getMainLooper()).post {
                delegate.onLinkClick(parsed)
            }
        }
    }
}

private class MobileRendererWebViewClient(
    private val agentConfig: AgentConfig,
    private val conversationEventListener: ConversationEventListener?,
    private val delegate: MobileRendererDelegate
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        val safeUrl = url?.let { Uri.parse(it).logSafeDescription() } ?: "<null>"
        Log.d(VOICE_TAG, "MobileRenderer: loading $safeUrl")
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        val safeUrl = error?.url?.let { Uri.parse(it).logSafeDescription() } ?: "<null>"
        if (conversationEventListener != null) {
            Log.w(VOICE_TAG, "Delegating renderer SSL error handling for URL $safeUrl")
            conversationEventListener.onReceivedSslError(view, handler, error)
        } else {
            Log.w(VOICE_TAG, "Cancelling renderer SSL error for URL $safeUrl")
            handler?.cancel()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            delegate.onMobileRendererError(IllegalStateException(error.description.toString()))
        }
    }
}
