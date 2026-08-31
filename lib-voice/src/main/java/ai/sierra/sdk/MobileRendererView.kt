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
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    fun onDisplayModeChanged(displayMode: MobileRendererDisplayMode)
}

internal enum class MobileRendererDisplayMode {
    INLINE,
    FULLSCREEN
}

internal class MobileRendererMessageBoundary(
    private val allowedOrigin: String,
    private val onOpen: () -> Unit,
    private val onSVPClientEvent: (String, List<Map<String, Any?>>) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onLinkClick: (String) -> Unit,
    private val onDisplayModeChanged: (MobileRendererDisplayMode) -> Unit
) {
    fun onPostMessage(data: String, sourceOrigin: String, isMainFrame: Boolean) {
        if (!isMainFrame || sourceOrigin != allowedOrigin) {
            return
        }
        val message = try {
            JSONObject(data)
        } catch (error: JSONException) {
            onError(error)
            return
        }
        when (message.optString("type")) {
            "onOpen" -> onOpen()
            "onSVPClientEvent" -> {
                val text = message.optString("text", "")
                val attachments = svpClientEventAttachments(message)
                if (text.isNotEmpty() || attachments.isNotEmpty()) {
                    onSVPClientEvent(text, attachments)
                }
            }
            "onError" -> {
                val reason = message.optString("reason", "unknown-renderer-error")
                onError(IllegalStateException(reason))
            }
            "onLinkClick" -> {
                val url = message.optString("url")
                if (url.isNotEmpty()) {
                    onLinkClick(url)
                }
            }
            "onDisplayModeChanged" -> {
                val displayMode = when (message.optString("displayMode")) {
                    "inline" -> MobileRendererDisplayMode.INLINE
                    "fullscreen" -> MobileRendererDisplayMode.FULLSCREEN
                    else -> return
                }
                onDisplayModeChanged(displayMode)
            }
        }
    }
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
    private val rendererOrigin = Uri.parse(agentConfig.apiHost.embedBaseURL)
    private val messageBoundary = MobileRendererMessageBoundary(
        allowedOrigin = rendererOrigin.toString(),
        onOpen = ::handleRendererOpen,
        onSVPClientEvent = delegate::onSVPClientEvent,
        onError = delegate::onMobileRendererError,
        onLinkClick = { delegate.onLinkClick(Uri.parse(it)) },
        onDisplayModeChanged = delegate::onDisplayModeChanged
    )
    // The state below is main-thread only: pushes and web messages post through mainHandler, and
    // WebView.evaluateJavascript callbacks land on the UI thread.
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
        if (setupWebView()) {
            loadRendererPage()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(): Boolean {
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
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            delegate.onMobileRendererError(
                UnsupportedOperationException("WebView does not support secure renderer messages")
            )
            return false
        }
        WebViewCompat.addWebMessageListener(
            webView,
            ANDROID_BRIDGE_NAME,
            setOf(rendererOrigin.toString())
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (message.type == WebMessageCompat.TYPE_STRING) {
                val data = message.data
                if (data != null) {
                    mainHandler.post {
                        if (!isDestroyed) {
                            messageBoundary.onPostMessage(data, sourceOrigin.toString(), isMainFrame)
                        }
                    }
                }
            }
        }
        if (agentConfig.apiHost == AgentAPIHost.LOCAL) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return true
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
        if (options.enableTextInput && options.enableLiveTranscription) {
            builder.appendQueryParameter("enableLiveTranscription", "true")
        }
        builder.appendQueryParameter("supportsLinkClick", "true")
        builder.appendQueryParameter("supportsFullscreen", "true")
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

    fun requestInlineDisplayMode(onComplete: (Boolean) -> Unit) {
        val js = """
            (function() {
              const fn = window.sierraMobile && window.sierraMobile.requestInlineDisplayMode;
              return typeof fn === 'function' ? fn() : false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            if (!isDestroyed) {
                onComplete(result == "true")
            }
        }
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, ANDROID_BRIDGE_NAME)
        }
        removeView(webView)
        webView.destroy()
    }

    private fun handleRendererOpen() {
        isReady = true
        webView.alpha = 1f
        flushPendingConversationEvents()
        flushPending()
    }

    private companion object {
        const val ANDROID_BRIDGE_NAME = "AndroidSDK"
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
