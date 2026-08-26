// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A plain Android View that hosts Sierra agent chat. */
@SuppressLint("ViewConstructor")
class AgentChatView internal constructor(
    context: Context,
    private val agentConfig: AgentConfig,
    private val options: AgentChatControllerOptions,
    private val conversationState: String?,
    private val listener: ConversationEventListener?,
    private val storage: ConversationStorage?,
    private val fileChooserLauncher: ((Intent) -> Unit)?,
    private val onConversationEndedInternal: (() -> Unit)?,
    private val onDispose: ((AgentChatView) -> Unit)?,
    viewId: Int,
) : FrameLayout(context) {
    private val webView: WebView
    private val loadingSpinner: ProgressBar
    /** Handler/runnable for the fallback reveal of a resumed conversation. */
    private val revealHandler = Handler(Looper.getMainLooper())
    private var revealFallbackRunnable: Runnable? = null
    /**
     * Whether the web content has been revealed (spinner hidden, web view faded in). The reveal
     * runs only once per load so repeated readiness signals don't re-trigger the animation.
     */
    private var didRevealContent = false
    /**
     * Flag used to keep track that of whether the web view successfully loaded or not. We only
     * restore state (and avoid reloading the URL) if the last load was successful.
     * */
    private var pageLoaded = false
    private var initialized = false
    private var hostResumed = false
    private var embedOpened = false
    private var reportedAppStatus: AppStatus? = null
    private var disposed = false
    private var initializeOnAttach = false
    private var initializationPosted = false
    private var pendingRestoreState: Bundle? = null
    private var observedLifecycleOwner: LifecycleOwner? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> onHostResume()
            Lifecycle.Event.ON_PAUSE -> onHostPause()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }
    /**
     * Callback for file chooser results from the WebView.
     * Used to pass selected file URIs back to the WebView's file input element.
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    // Tracks in-flight addAgentTags requests by callback ID, with a monotonic counter for IDs.
    // Both are confined to the main thread (addAgentTags marshals onto it before touching them).
    private val pendingAddAgentTagsCallbacks = mutableMapOf<String, PendingAddAgentTags>()
    private var nextAddAgentTagsCallbackID = 0

    private class PendingAddAgentTags(
        val callback: (Boolean) -> Unit,
        val timeout: Runnable,
    )

    init {
        id = viewId
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        options.chatStyle.colors.background?.let { setBackgroundColor(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(context) {}
        }
        webView = createWebView()
        loadingSpinner = ProgressBar(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            visibility = View.VISIBLE
        }
        addView(webView)
        addView(loadingSpinner)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val chatWebViewClient = ChatWebViewClient(this, agentConfig, listener)
        return WebView(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Keep the web content hidden until the embed reports it is ready,
            // preventing transient web loading states from flashing onscreen.
            alpha = 0f
            // Set background color to match chat style to avoid white flash while loading
            options.chatStyle.colors.background?.let { setBackgroundColor(it) }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = generateUserAgent(context)
            // Sierra WebView hardening (CWE-693). Inlined adjacent to the WebView construction so
            // SAST tools recognize the defenses; do not factor into a helper.
            settings.allowFileAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = chatWebViewClient
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    // Cancel any pending callback
                    this@AgentChatView.filePathCallback?.onReceiveValue(null)
                    this@AgentChatView.filePathCallback = filePathCallback

                    val launcher = fileChooserLauncher
                    if (launcher == null) {
                        this@AgentChatView.filePathCallback?.onReceiveValue(null)
                        this@AgentChatView.filePathCallback = null
                        return false
                    }
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    return try {
                        launcher(intent)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot launch file chooser", e)
                        this@AgentChatView.filePathCallback?.onReceiveValue(null)
                        this@AgentChatView.filePathCallback = null
                        false
                    }
                }
            }
            addJavascriptInterface(
                ChatWebViewInterface(
                    context = context,
                    storage = storage,
                    listener = listener,
                    chatView = this@AgentChatView,
                    webView = this,
                    conversationOptions = options.conversationOptions,
                    onConversationEndedInternal = onConversationEndedInternal,
                ),
                "AndroidSDK",
            )
        }.also {
            if (agentConfig.apiHost == AgentAPIHost.LOCAL) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }

    /** Loads the chat, restoring [savedInstanceState] when it contains compatible WebView state. */
    internal fun initialize(savedInstanceState: Bundle? = null) {
        if (initialized || disposed) {
            return
        }
        initialized = true
        initializeOnAttach = false

        val stateToRestore = savedInstanceState ?: pendingRestoreState
        pendingRestoreState = null

        if (stateToRestore != null && restoreCompatibleState(stateToRestore)) {
            return
        }

        webView.loadUrl(buildChatUrl())
    }

    /** Initializes after hierarchy state restoration has a chance to run. */
    internal fun initializeWhenAttached() {
        if (initialized || disposed) {
            return
        }
        initializeOnAttach = true
        if (isAttachedToWindow) {
            postInitialization()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeHostLifecycle()
        if (initializeOnAttach) {
            postInitialization()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    private fun observeHostLifecycle() {
        val owner = findViewTreeLifecycleOwner() ?: return
        if (owner === observedLifecycleOwner) {
            return
        }
        stopObservingHostLifecycle()
        observedLifecycleOwner = owner
        owner.lifecycle.addObserver(lifecycleObserver)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onHostResume()
        } else {
            onHostPause()
        }
    }

    private fun stopObservingHostLifecycle() {
        observedLifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        observedLifecycleOwner = null
    }

    private fun postInitialization() {
        if (initialized || disposed || initializationPosted) {
            return
        }
        initializationPosted = true
        post {
            initializationPosted = false
            if (isAttachedToWindow && initializeOnAttach) {
                initialize()
            }
        }
    }

    private fun buildChatUrl(): String {
        // Turn config and options into query parameters that the Android web embed expects.
        val urlBuilder = Uri.parse(agentConfig.url).buildUpon()
        if (!agentConfig.target.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("target", agentConfig.target)
        }

        // Should match the web embed's Brand shape.
        val brandMap = mutableMapOf<String, Any>(
            "botName" to options.name,
            "greetingMessage" to options.greetingMessage,
            "errorMessage" to options.errorMessage,
            "inactivityMessage" to (options.inactivityMessage ?: ""),
            "agentTransferWaitingMessage" to options.agentTransferWaitingMessage,
            "agentTransferQueueSizeMessage" to options.agentTransferQueueSizeMessage,
            "agentTransferQueueNextMessage" to options.agentTransferQueueNextMessage,
            "agentJoinedMessage" to options.agentJoinedMessage,
            "agentLeftMessage" to options.agentLeftMessage,
            "chatStyle" to JSONObject(options.chatStyle.toJSON()).toString(),
            "messageLabelPlacement" to options.messageLabelPlacement.value,
        )
        options.showTimestamps?.let { brandMap["showTimestamps"] = it }
        options.showSpeakerLabels?.let { brandMap["showBotName"] = it }
        options.showAvatars?.let { brandMap["showAvatars"] = it }
        options.agentAvatarURL?.let { brandMap["agentAvatarURL"] = it }
        options.sendButtonSVG?.let { brandMap["sendButtonSVG"] = it }
        options.sendButtonDisabledSVG?.let { brandMap["sendButtonDisabledSVG"] = it }
        // If locale auto-detect or server-configured chat strings are enabled, remove any messages
        // that are set to their default value so server-configured values or locale defaults can win.
        if (options.shouldOmitDefaultChatStrings()) {
            if (!options.hasCustomGreetingMessage()) {
                brandMap.remove("greetingMessage")
            }
            if (options.errorMessage == AgentChatControllerOptions.DEFAULTS.errorMessage) {
                brandMap.remove("errorMessage")
            }
            if (options.agentTransferWaitingMessage == AgentChatControllerOptions.DEFAULTS.agentTransferWaitingMessage) {
                brandMap.remove("agentTransferWaitingMessage")
            }
            if (options.agentTransferQueueSizeMessage == AgentChatControllerOptions.DEFAULTS.agentTransferQueueSizeMessage) {
                brandMap.remove("agentTransferQueueSizeMessage")
            }
            if (options.agentTransferQueueNextMessage == AgentChatControllerOptions.DEFAULTS.agentTransferQueueNextMessage) {
                brandMap.remove("agentTransferQueueNextMessage")
            }
            if (options.agentJoinedMessage == AgentChatControllerOptions.DEFAULTS.agentJoinedMessage) {
                brandMap.remove("agentJoinedMessage")
            }
            if (options.agentLeftMessage == AgentChatControllerOptions.DEFAULTS.agentLeftMessage) {
                brandMap.remove("agentLeftMessage")
            }
        }
        urlBuilder.appendQueryParameter("brand", JSONObject(brandMap as Map<*, *>).toString())

        // Subset of the web embed's chat UI strings.
        val chatInterfaceStringsMap = mutableMapOf(
            "inputPlaceholder" to options.inputPlaceholder,
            "disclosure" to (options.disclosure ?: ""),
            "conversationEndedMessage" to options.conversationEndedMessage,
            "newChatButtonLabel" to options.newChatButtonLabel,
            "printTranscriptMenuLabel" to options.saveTranscriptLabel,
            "endConversationMenuLabel" to options.endConversationLabel,
        )
        if (options.shouldOmitDefaultChatStrings()) {
            if (options.newChatButtonLabel == AgentChatControllerOptions.DEFAULTS.newChatButtonLabel) {
                chatInterfaceStringsMap.remove("newChatButtonLabel")
            }
            if (options.saveTranscriptLabel == AgentChatControllerOptions.DEFAULTS.saveTranscriptLabel) {
                chatInterfaceStringsMap.remove("printTranscriptMenuLabel")
            }
            if (options.endConversationLabel == AgentChatControllerOptions.DEFAULTS.endConversationLabel) {
                chatInterfaceStringsMap.remove("endConversationMenuLabel")
            }
        }
        urlBuilder.appendQueryParameter(
            "chatInterfaceStrings",
            JSONObject(chatInterfaceStringsMap as Map<*, *>).toString(),
        )

        if (options.hideTitleBar) {
            urlBuilder.appendQueryParameter("hideTitleBar", "true")
        }
        urlBuilder.appendQueryParameter("persistenceMode", "custom")
        val conversationOptions = options.conversationOptions ?: ConversationOptions()
        // The custom greeting was initially a UI-only concept and thus specified via AgentChatControllerOptions,
        // but it now also affects the API, so it's in ConversationOptions. Read it from both places
        // so that old clients don't need to change anything.
        var customGreeting = conversationOptions.customGreeting
        if (customGreeting == null && options.shouldUseGreetingMessageAsCustomGreeting()) {
            customGreeting = options.greetingMessage
        }

        val locale = conversationOptions.locale ?: resources.configuration.locales[0]
        urlBuilder.appendQueryParameter("locale", locale.toLanguageTag())
        // Variables and secrets are intentionally not added to the URL. They are delivered to the
        // web embed via the AndroidSDK.getInitialMemory() bridge method (see ChatWebViewInterface)
        // so they cannot leak into device, proxy, or analytics logs.
        if (customGreeting != null) {
            urlBuilder.appendQueryParameter("greeting", customGreeting)
        }
        urlBuilder.appendQueryParameter(
            "enableContactCenter",
            conversationOptions.enableContactCenter.toString(),
        )
        if (options.canPrintTranscript) {
            urlBuilder.appendQueryParameter("canPrintTranscript", "true")
        }
        if (options.canEndConversation) {
            urlBuilder.appendQueryParameter("canEndConversation", "true")
        }
        if (options.confirmEndConversation) {
            urlBuilder.appendQueryParameter("confirmEndConversation", "true")
        }
        if (options.confirmEndConversationMode == EndConversationConfirmationMode.LIVE_CHAT) {
            urlBuilder.appendQueryParameter(
                "confirmEndConversationMode",
                options.confirmEndConversationMode.value,
            )
        }
        if (options.footerEndConversationButton) {
            urlBuilder.appendQueryParameter("footerEndConversationButton", "true")
        }
        if (options.canStartNewChat) {
            urlBuilder.appendQueryParameter("canStartNewChat", "true")
        }
        if (!options.initialUserMessage.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("initialUserMessage", options.initialUserMessage)
        }
        if (options.startAtTop) {
            urlBuilder.appendQueryParameter("startAtTop", "true")
        }
        if (options.showScrollToBottom) {
            urlBuilder.appendQueryParameter("showScrollToBottom", "true")
        }
        if (options.pinDisclosure) {
            urlBuilder.appendQueryParameter("pinDisclosure", "true")
        }
        if (options.disclosurePlacement != DisclosurePlacement.CONVERSATION) {
            urlBuilder.appendQueryParameter(
                "disclosurePlacement",
                options.disclosurePlacement.value,
            )
        }
        if (options.removeInputDivider) {
            urlBuilder.appendQueryParameter("removeInputDivider", "true")
        }
        if (options.useConfiguredChatStrings) {
            urlBuilder.appendQueryParameter("useConfiguredChatStrings", "true")
        }
        if (options.useConfiguredStyle) {
            urlBuilder.appendQueryParameter("useConfiguredStyle", "true")
        }
        if (options.autoDetectChatStrings != null) {
            urlBuilder.appendQueryParameter(
                "autoDetectChatStrings",
                options.autoDetectChatStrings.toString(),
            )
        }
        if (options.autoUpdateChatStrings != null) {
            urlBuilder.appendQueryParameter(
                "autoUpdateChatStrings",
                options.autoUpdateChatStrings.toString(),
            )
        }
        options.textDirection?.let {
            urlBuilder.appendQueryParameter("textDirection", it.value)
        }
        if (!options.userIdentityToken.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("userIdentityToken", options.userIdentityToken)
        }
        if (!conversationState.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("state", conversationState)
        }
        if (options.enableConversationList) {
            urlBuilder.appendQueryParameter("enableConversationList", "true")
        }
        if (options.showConversationListByDefault) {
            urlBuilder.appendQueryParameter("showConversationListByDefault", "true")
        }
        if (options.updateVariablesAndSecretsOnSessionResume) {
            urlBuilder.appendQueryParameter("updateVariablesAndSecretsOnSessionResume", "true")
        }

        return urlBuilder.build().toString()
    }

    /** Delivers the result produced by the host's file chooser launcher. */
    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    /** Saves WebView and conversation storage state into [outState]. */
    internal fun saveState(outState: Bundle) {
        webView.saveState(outState)
        outState.putBoolean(STATE_PAGE_LOADED, pageLoaded)
        outState.putParcelable(
            STATE_ARGS,
            AgentChatFragmentArgs(agentConfig, options, conversationState),
        )
        storage?.getAll()?.let { outState.putSerializable(STATE_STORAGE, HashMap(it)) }
    }

    override fun onSaveInstanceState(): Parcelable {
        return SavedState(super.onSaveInstanceState(), Bundle().also(::saveState))
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        if (disposed) {
            return
        }
        if (initialized) {
            restoreCompatibleState(state.chatState)
        } else {
            pendingRestoreState = state.chatState
            initializeWhenAttached()
        }
    }

    private fun restoreCompatibleState(savedInstanceState: Bundle): Boolean {
        val args = AgentChatFragmentArgs(agentConfig, options, conversationState)
        // Preserve the WebView document across configuration changes when the SDK inputs are
        // unchanged. Hosts that rebuild options with new adaptive colors still fall through to
        // loadUrl below because the saved args no longer match the current args.
        if (!savedInstanceState.getBoolean(STATE_PAGE_LOADED) ||
            savedInstanceState.getParcelable<AgentChatFragmentArgs>(STATE_ARGS) != args
        ) {
            return false
        }
        pageLoaded = true
        restoreStorage(savedInstanceState)
        showWebContent()
        webView.restoreState(savedInstanceState)
        return true
    }

    private fun restoreStorage(savedInstanceState: Bundle) {
        @Suppress("DEPRECATION")
        val savedStorage = savedInstanceState.getSerializable(STATE_STORAGE)
            as? HashMap<String, String>
        savedStorage?.forEach { (key, value) -> storage?.setItem(key, value) }
    }

    internal fun showWebContent() {
        if (disposed) {
            return
        }
        revealFallbackRunnable?.let { revealHandler.removeCallbacks(it) }
        revealFallbackRunnable = null
        if (didRevealContent) {
            return
        }
        didRevealContent = true
        loadingSpinner.visibility = View.GONE
        webView.animate().alpha(1f).setDuration(300).start()
    }

    /**
     * Keeps the spinner up for a resumed conversation until [showWebContent] is triggered by the
     * embed's onConversationReady signal. The scheduled fallback guards against older embeds that
     * never send that signal, so the spinner is not left up indefinitely.
     */
    internal fun scheduleRevealFallback() {
        if (disposed || didRevealContent || revealFallbackRunnable != null) {
            return
        }
        val runnable = Runnable { showWebContent() }
        revealFallbackRunnable = runnable
        revealHandler.postDelayed(runnable, REVEAL_FALLBACK_MS)
    }

    internal fun stopLoadingIndicator() {
        loadingSpinner.visibility = View.GONE
    }

    internal fun setPageLoaded(loaded: Boolean) {
        pageLoaded = loaded
    }

    internal fun onHostResume() {
        hostResumed = true
        dispatchAppStatusChange(AppStatus.FOREGROUNDED)
    }

    internal fun onHostPause() {
        hostResumed = false
        dispatchAppStatusChange(AppStatus.BACKGROUNDED)
    }

    internal fun onEmbedOpened() {
        embedOpened = true
        dispatchAppStatusChange(
            if (hostResumed) AppStatus.FOREGROUNDED else AppStatus.BACKGROUNDED,
        )
    }

    private fun dispatchAppStatusChange(status: AppStatus) {
        if (disposed || !embedOpened || reportedAppStatus == status) {
            return
        }
        reportedAppStatus = status
        val localTimestampMs = System.currentTimeMillis()
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('appstatuschange', " +
                "{ detail: { status: '${status.value}', localTimestampMs: $localTimestampMs } }))",
            null,
        )
    }

    /** Releases callbacks owned by this view. The host should call this when discarding the view. */
    fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        initializeOnAttach = false
        pendingRestoreState = null
        stopObservingHostLifecycle()
        revealFallbackRunnable?.let { revealHandler.removeCallbacks(it) }
        revealFallbackRunnable = null
        pendingAddAgentTagsCallbacks.values.forEach {
            revealHandler.removeCallbacks(it.timeout)
            it.callback(false)
        }
        pendingAddAgentTagsCallbacks.clear()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        onDispose?.invoke(this)
    }

    fun printTranscript() {
        webView.evaluateJavascript("sierraAndroid.printTranscript()", null)
    }

    fun endConversation() {
        webView.evaluateJavascript("sierraAndroid.endConversation()", null)
    }

    fun sendUserAttachment(attachments: List<UserAttachment>) {
        val payload = serializedAttachments(attachments)
        webView.evaluateJavascript(
            "sierraAndroid.sendUserAttachment(JSON.parse($payload))",
            null,
        )
    }

    fun sendUserMessage(message: String, attachments: List<UserAttachment>) {
        val payload = serializedAttachments(attachments)
        val messageJSON = JSONObject.quote(message).escapeJsLineSeparators()
        webView.evaluateJavascript(
            "sierraAndroid.sendUserMessage($messageJSON, JSON.parse($payload))",
            null,
        )
    }

    private fun serializedAttachments(attachments: List<UserAttachment>): String =
        JSONObject.quote(
            JSONArray().apply {
                attachments.forEach { attachment -> put(attachment.toJSONObject()) }
            }.toString(),
        ).escapeJsLineSeparators()

    fun addAgentTags(
        tags: List<String>,
        options: AddAgentTagsOptions,
        callback: (Boolean) -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            revealHandler.post { addAgentTags(tags, options, callback) }
            return
        }
        if (!initialized) {
            callback(false)
            return
        }

        val callbackId = "addAgentTags_${System.currentTimeMillis()}_${nextAddAgentTagsCallbackID++}"
        val timeout = Runnable {
            pendingAddAgentTagsCallbacks.remove(callbackId)?.callback?.invoke(false)
        }
        pendingAddAgentTagsCallbacks[callbackId] = PendingAddAgentTags(callback, timeout)

        // Escape U+2028/U+2029 so tag values cannot break the injected JavaScript source.
        val tagsJSON = JSONObject.quote(JSONArray(tags).toString()).escapeJsLineSeparators()
        val optionsJSON = options.toJSONObject().toString().escapeJsLineSeparators()
        webView.evaluateJavascript(
            """
            (function() {
                const finish = function(added) {
                    window.AndroidSDK.onAddAgentTagsResult(${JSONObject.quote(callbackId)}, Boolean(added));
                };
                const fail = function() { finish(false); };
                const api = window.sierraAndroid;
                if (!api || typeof api.addAgentTags !== 'function') {
                    fail();
                    return;
                }
                Promise.resolve()
                    .then(function() {
                        return api.addAgentTags(JSON.parse($tagsJSON), $optionsJSON);
                    })
                    .then(finish)
                    .catch(fail);
            })();
            """.trimIndent(),
            null,
        )
        revealHandler.postDelayed(timeout, ADD_AGENT_TAGS_TIMEOUT_MS)
    }

    internal fun onAddAgentTagsResult(callbackId: String, added: Boolean) {
        revealHandler.post {
            val pending = pendingAddAgentTagsCallbacks.remove(callbackId) ?: return@post
            revealHandler.removeCallbacks(pending.timeout)
            pending.callback(added)
        }
    }

    fun showConversationList() {
        webView.evaluateJavascript("sierraAndroid.showConversationList()", null)
    }

    private class SavedState : BaseSavedState {
        val chatState: Bundle

        constructor(superState: Parcelable?, chatState: Bundle) : super(superState) {
            this.chatState = chatState
        }

        private constructor(parcel: Parcel) : super(parcel) {
            // chatState holds AgentChatFragmentArgs, so it must be unparcelled with a class loader
            // that can see SDK classes. Bundle's own loader is the boot loader, which cannot.
            chatState = parcel.readBundle(javaClass.classLoader) ?: Bundle()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeBundle(chatState)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(parcel: Parcel) = SavedState(parcel)

            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }

}

private fun generateUserAgent(context: Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion = packageInfo.versionName ?: "0"
    val appName = context.packageName
    val androidVersion = Build.VERSION.RELEASE
    val model = Build.MODEL
    return "Sierra-Android-SDK ($appName/$appVersion $model/$androidVersion) WebView"
}

private class ChatWebViewClient(
    private val chatView: AgentChatView,
    private val agentConfig: AgentConfig,
    private val listener: ConversationEventListener?,
) : WebViewClient() {
    private var hadError = false

    private fun handleMainUrlLoadFailure(view: WebView?) {
        view?.loadUrl("about:blank")
        chatView.setPageLoaded(false)
        hadError = true
        chatView.stopLoadingIndicator()
        listener?.onConversationInitializationError()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url.toString().startsWith(agentConfig.url) && !hadError) {
            chatView.setPageLoaded(true)
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (listener != null) {
            Log.w(TAG, "Delegating SSL error handling to conversation listener")
            listener.onReceivedSslError(view, handler, error)
            return
        }
        handler?.cancel()
        if (error?.url?.startsWith(agentConfig.url) == true) {
            handleMainUrlLoadFailure(view)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.url.toString().startsWith(agentConfig.url)) {
            Log.e(TAG, "Received error trying to load the main URL")
            handleMainUrlLoadFailure(view)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val baseUri = Uri.parse(agentConfig.url)
        if (request.isForMainFrame && (url.host != baseUri.host || url.scheme != baseUri.scheme)) {
            if (listener?.onLinkClick(url) == true) {
                return true
            }
            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val context = view?.context ?: return false
            Handler(Looper.getMainLooper()).post { context.startActivity(intent) }
            return true
        }
        return false
    }
}

private class ChatWebViewInterface(
    private val context: Context,
    private val storage: ConversationStorage?,
    private val listener: ConversationEventListener?,
    private val chatView: AgentChatView,
    private val webView: WebView,
    private val conversationOptions: ConversationOptions?,
    private val onConversationEndedInternal: (() -> Unit)?,
) {
    private val handler = Handler(Looper.getMainLooper())

    private fun handleOnOpen(isNewConversation: Boolean) {
        handler.post {
            chatView.onEmbedOpened()
            if (isNewConversation) {
                // New conversation: the greeting is already rendered, so reveal now.
                chatView.showWebContent()
            } else {
                // Resuming an existing conversation: keep the spinner up until the transcript has
                // rendered (onConversationReady) so we don't flash an empty greeting state. A
                // fallback guards against older embeds that never send onConversationReady.
                chatView.scheduleRevealFallback()
            }
        }
        listener?.onOpen(isNewConversation)
    }

    @JavascriptInterface
    fun onConversationReady() {
        handler.post { chatView.showWebContent() }
    }

    @JavascriptInterface
    fun onOpen() {
        handleOnOpen(true)
    }

    @JavascriptInterface
    fun onOpen(isNewConversation: Boolean) {
        handleOnOpen(isNewConversation)
    }

    @JavascriptInterface
    fun onTransfer(dataJSONStr: String) {
        val dataJSON = try {
            JSONObject(dataJSONStr)
        } catch (e: JSONException) {
            Log.e(TAG, "Cannot parse transfer JSON data", e)
            return
        }
        val dataMap = mutableMapOf<String, String>()
        dataJSON.optJSONArray("data")?.let { dataArray ->
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                dataMap[item.getString("key")] = item.getString("value")
            }
        }
        listener?.onConversationTransfer(
            ConversationTransfer(
                dataJSON.optBoolean("isSynchronous"),
                dataJSON.optBoolean("isContactCenter"),
                dataMap,
            ),
        )
    }

    private fun createWebPrintJob(webView: WebView) {
        (context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { printManager ->
            val jobName = "Chat Transcript"
            printManager.print(
                jobName,
                webView.createPrintDocumentAdapter(jobName),
                PrintAttributes.Builder().build(),
            )
        }
    }

    @JavascriptInterface
    fun onPrint(url: String, data: String) {
        var heldWebView: WebView? = null
        fun doWebViewPrint() {
            // Create a WebView object specifically for printing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WebView.startSafeBrowsing(context) {}
            }
            val printWebView = WebView(context)
            // Sierra WebView hardening (CWE-693). Inlined adjacent to the WebView construction so
            // SAST tools recognize the defenses; do not factor into a helper.
            printWebView.settings.allowFileAccess = false
            @Suppress("DEPRECATION")
            printWebView.settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            printWebView.settings.allowUniversalAccessFromFileURLs = false
            printWebView.settings.allowContentAccess = false
            printWebView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            printWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ) = false

                override fun onPageFinished(view: WebView, url: String) {
                    createWebPrintJob(view)
                    heldWebView = null
                }
            }
            printWebView.postUrl(url, data.toByteArray())
            // Keep a reference to WebView object until you pass the PrintDocumentAdapter
            // to the PrintManager
            heldWebView = printWebView
        }
        handler.post { doWebViewPrint() }
    }

    @JavascriptInterface
    fun onConversationStart(conversationID: String) {
        listener?.onConversationStart(conversationID)
    }

    @JavascriptInterface
    fun onAgentMessageEnd() {
        listener?.onAgentMessageEnd()
    }

    @JavascriptInterface
    fun onExternalAgentJoin(externalConversationID: String?, externalAgentID: String?) {
        listener?.onExternalAgentJoin(externalConversationID, externalAgentID)
    }

    @JavascriptInterface
    fun onEndChat() {
        listener?.onConversationEnded()
        onConversationEndedInternal?.invoke()
    }

    @JavascriptInterface
    fun onShowConversationList() {
        listener?.onShowConversationList()
    }

    @JavascriptInterface
    fun onHideConversationList() {
        listener?.onHideConversationList()
    }

    @JavascriptInterface
    fun onAddAgentTagsResult(callbackId: String, added: Boolean) {
        chatView.onAddAgentTagsResult(callbackId, added)
    }

    @JavascriptInterface
    fun storeValue(key: String, value: String) {
        storage?.setItem(key, value)
    }

    @JavascriptInterface
    fun getStoredValue(key: String): String? = storage?.getItem(key)

    @JavascriptInterface
    fun clearStorage() {
        storage?.clear()
    }

    /**
     * Returns the initial agent memory (variables and secrets) as a JSON string. These are
     * delivered to the web embed via this bridge method instead of URL query parameters, so the
     * values cannot leak into device, proxy, or analytics logs.
     */
    @JavascriptInterface
    fun getInitialMemory(): String {
        val memory = JSONObject()
        conversationOptions?.variables?.takeIf { it.isNotEmpty() }?.let {
            memory.put("variables", JSONObject(it))
        }
        conversationOptions?.secrets?.takeIf { it.isNotEmpty() }?.let {
            memory.put("secrets", JSONObject(it))
        }
        return memory.toString()
    }

    @JavascriptInterface
    fun onSecretExpiry(secretName: String, callbackId: String) {
        listener?.onSecretExpiry(secretName) { result ->
            resolveCallback(callbackId, result)
        }
    }

    @JavascriptInterface
    fun onUserIdentityTokenExpiry(callbackId: String) {
        listener?.onUserIdentityTokenExpiry { result ->
            resolveCallback(callbackId, result)
        }
    }

    private fun resolveCallback(callbackId: String, result: SecretExpiryResult) {
        val jsCode = when (result) {
            is SecretExpiryResult.Success -> {
                val valueJSON = result.value?.let { JSONObject.quote(it) } ?: "null"
                "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, $valueJSON);"
            }
            is SecretExpiryResult.Error -> {
                "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, null, ${JSONObject.quote(result.message)});"
            }
        }
        handler.post { webView.evaluateJavascript(jsCode, null) }
    }
}

private const val TAG = "AgentChatView"
private const val STATE_PAGE_LOADED = "pageLoaded"
private const val STATE_ARGS = "args"
private const val STATE_STORAGE = "storage"
/**
 * How long to keep the spinner up for a resumed conversation while waiting for
 * onConversationReady. Only reached when the embed does not send that signal (e.g. an older embed
 * build); the normal path reveals as soon as the transcript has rendered.
 */
private const val REVEAL_FALLBACK_MS = 10_000L
private const val ADD_AGENT_TAGS_TIMEOUT_MS = 30_000L

private enum class AppStatus(val value: String) {
    FOREGROUNDED("FOREGROUNDED"),
    BACKGROUNDED("BACKGROUNDED"),
}

/**
 * U+2028 and U+2029 are valid in JSON strings but line terminators in JavaScript source, so JSON
 * embedded in an evaluateJavascript payload must escape them to keep the script parseable.
 */
private fun String.escapeJsLineSeparators(): String =
    replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
