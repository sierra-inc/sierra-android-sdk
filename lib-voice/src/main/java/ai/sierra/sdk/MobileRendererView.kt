// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
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
    private val webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    // The state below is main-thread only: pushes post through mainHandler, JS bridge callbacks
    // re-post to mainHandler, and WebView.evaluateJavascript callbacks land on the UI thread.
    // Keep all reads/writes on the main thread so no additional synchronization is needed.
    private var isReady = false
    private var isDestroyed = false
    private val pendingBatches = mutableListOf<String>()
    private val pendingConversationEvents = mutableListOf<String>()
    private val queuedConversationEvents = mutableListOf<String>()
    private var isConversationEventFlushScheduled = false
    private var conversationEventFlushFailureCount = 0
    private val conversationEventFlushRunnable = Runnable { flushQueuedConversationEvents() }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(context) {}
        }
        webView = WebView(context)
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
        // Sierra WebView hardening (CWE-693). Inlined adjacent to the WebView construction so
        // SAST tools recognize the defenses; do not factor into a helper.
        webView.settings.allowFileAccess = false
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.allowContentAccess = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webViewClient = MobileRendererWebViewClient(agentConfig, conversationEventListener, delegate)
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
        val messageStyleJSON = options.voiceStyle.messageStyleJSONString()
        if (messageStyleJSON.isNotEmpty()) {
            builder.appendQueryParameter("messageStyle", messageStyleJSON)
        }
        builder.appendQueryParameter("supportsLinkClick", "true")
        webView.loadUrl(builder.build().toString())
    }

    fun pushAttachments(attachments: List<Map<String, Any?>>) {
        if (isDestroyed) {
            return
        }
        val json = JSONArray(attachments).toString()
        if (isReady) {
            evaluatePushAttachments(json)
        } else {
            pendingBatches.add(json)
        }
    }

    fun pushConversationEvent(event: AgentVoiceConversationEvent) {
        if (isDestroyed) {
            return
        }
        val attachments = JSONArray()
        event.attachments.forEach { attachments.put(JSONObject(it)) }
        val raw = JSONObject()
            .put("messageId", event.messageId)
            .put("eventType", event.eventType)
            .put("role", event.role)
            .put("text", event.text)
            .put("attachments", attachments)
        val json = raw.toString()
        if (isReady) {
            enqueueConversationEvent(json)
        } else {
            pendingConversationEvents.add(json)
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

    private fun enqueueConversationEvent(json: String) {
        queuedConversationEvents.add(json)
        scheduleConversationEventFlush()
    }

    private fun scheduleConversationEventFlush(delayMs: Long = CONVERSATION_EVENT_FLUSH_DELAY_MS) {
        if (isConversationEventFlushScheduled) {
            return
        }
        isConversationEventFlushScheduled = true
        mainHandler.postDelayed(conversationEventFlushRunnable, delayMs)
    }

    private fun flushQueuedConversationEvents() {
        if (isDestroyed || !isReady || queuedConversationEvents.isEmpty()) {
            isConversationEventFlushScheduled = false
            return
        }
        val events = queuedConversationEvents.toList()
        queuedConversationEvents.clear()
        evaluatePushConversationEvents(events) { didPush ->
            isConversationEventFlushScheduled = false
            if (!didPush) {
                queuedConversationEvents.addAll(0, events)
                conversationEventFlushFailureCount += 1
                if (conversationEventFlushFailureCount < MAX_CONVERSATION_EVENT_FLUSH_FAILURES) {
                    scheduleConversationEventFlush(delayMs = CONVERSATION_EVENT_RETRY_DELAY_MS)
                    return@evaluatePushConversationEvents
                }
                delegate.onMobileRendererError(
                    IllegalStateException("pushConversationEvents is not available")
                )
                return@evaluatePushConversationEvents
            }
            conversationEventFlushFailureCount = 0
            if (queuedConversationEvents.isNotEmpty()) {
                scheduleConversationEventFlush()
            }
        }
    }

    private fun evaluatePushConversationEvents(
        events: List<String>,
        onComplete: (Boolean) -> Unit
    ) {
        val eventArray = JSONArray(events)
        val escaped = JSONObject.quote(eventArray.toString())
        val js = """
            (function() {
              const fn = window.sierraMobile && window.sierraMobile.pushConversationEvents;
              if (typeof fn === 'function') {
                fn($escaped);
                return true;
              }
              return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            if (isDestroyed) {
                return@evaluateJavascript
            }
            onComplete(result == "true")
        }
    }

    private fun flushPendingConversationEvents() {
        val pending = pendingConversationEvents.toList()
        pendingConversationEvents.clear()
        pending.forEach { enqueueConversationEvent(it) }
    }

    fun destroy() {
        isDestroyed = true
        isReady = false
        isConversationEventFlushScheduled = false
        mainHandler.removeCallbacks(conversationEventFlushRunnable)
        pendingBatches.clear()
        pendingConversationEvents.clear()
        queuedConversationEvents.clear()
        webView.stopLoading()
        webView.removeJavascriptInterface("AndroidSDK")
        removeView(webView)
        webView.destroy()
    }

    private inner class RendererBridge {
        @JavascriptInterface
        fun onOpen() {
            mainHandler.post {
                if (isDestroyed) {
                    return@post
                }
                isReady = true
                webView.alpha = 1f
                flushPendingConversationEvents()
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
            mainHandler.post {
                if (isDestroyed) {
                    return@post
                }
                delegate.onLinkClick(parsed)
            }
        }
    }

    private companion object {
        const val CONVERSATION_EVENT_FLUSH_DELAY_MS = 16L
        const val CONVERSATION_EVENT_RETRY_DELAY_MS = 250L
        const val MAX_CONVERSATION_EVENT_FLUSH_FAILURES = 3
    }
}

private class MobileRendererWebViewClient(
    private val agentConfig: AgentConfig,
    private val conversationEventListener: ConversationEventListener?,
    private val delegate: MobileRendererDelegate
) : WebViewClient() {
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (conversationEventListener != null) {
            conversationEventListener.onReceivedSslError(view, handler, error)
        } else {
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
