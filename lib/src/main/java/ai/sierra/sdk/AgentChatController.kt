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
import android.os.Parcelable
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


/**
 * Controls whether the message label (speaker name and timestamp) is shown
 * above or below chat message bubbles.
 */
enum class MessageLabelPlacement(val value: String) {
    /** Use the server-configured value from the Style panel. */
    DEFAULT(""),
    /** Show the message label above chat bubbles. */
    ABOVE("above"),
    /** Show the message label below chat bubbles. */
    BELOW("below"),
}

/**
 * Controls which view(s) the disclosure text is displayed in.
 */
enum class DisclosurePlacement(val value: String) {
    /** Display the disclosure above the conversation transcript. */
    CONVERSATION("conversation"),
    /**
     * Display the disclosure below the "start new chat" button in the conversation
     * list, and not in the conversation itself. Requires enableConversationList.
     */
    CONVERSATION_LIST("conversationList"),
    /** Display the disclosure in both views. */
    BOTH("both"),
}

/**
 * Controls the text direction of the chat interface.
 */
enum class TextDirection(val value: String) {
    /** Left-to-right layout (default). */
    LTR("ltr"),
    /** Right-to-left layout, for languages like Arabic and Hebrew. */
    RTL("rtl"),
    /** Automatically configured from the conversation locale. */
    AUTO("auto"),
}

/** Options for adding tags to the active conversation. */
data class AddAgentTagsOptions(
    /** Add tags as developer-only tags. */
    val dev: Boolean? = null,
    /** Skip tags already present on the conversation. */
    val omitPresent: Boolean? = null,
    /** Store `name:value` tags as custom fields visible in Agent Studio. */
    val customField: Boolean? = null,
) {
    internal fun toJSONObject(): JSONObject = JSONObject().apply {
        dev?.let { put("dev", it) }
        omitPresent?.let { put("omitPresent", it) }
        customField?.let { put("customField", it) }
    }
}

/** Options for configuring an agent chat controller. */
@Parcelize
data class AgentChatControllerOptions(
    /** Name for this virtual agent, displayed as the navigation item title. */
    val name: String,

    /**
     * Use chat interface strings configured on the server (greeting, error messages, etc.),
     * including server-managed locale/direction settings for those strings.
     * When enabled, server-configured values take precedence over local string options.
     */
    val useConfiguredChatStrings: Boolean = false,

    /**
     * Use styling configured on the server (colors, typography, logo, etc.).
     * When enabled, server-configured styles take precedence over local chatStyle.
     */
    val useConfiguredStyle: Boolean = false,

    /**
     * Message shown from the agent when starting the conversation.
     * Overridden by server-configured greeting message if useConfiguredChatStrings is true.
     */
    var greetingMessage: String = "How can I help you today?",

    /**
     * Secondary text to display above the agent message at the start of a conversation.
     * Overridden by server-configured disclosure if useConfiguredChatStrings is true.
     */
    var disclosure: String? = null,

    /**
     * Message shown when an error is encountered during the conversation.
     * Overridden by server-configured error message if useConfiguredChatStrings is true.
     */
    var errorMessage: String = "Oops, an error was encountered! Please try again.",

    /**
     * Message shown when a conversation was ended due to inactivity.
     * Overridden by server-configured inactivity message if useConfiguredChatStrings is true.
     */
    var inactivityMessage: String? = null,

    /**
     * Placeholder value displayed in the chat input when it is empty.
     * Overridden by server-configured input placeholder if useConfiguredChatStrings is true.
     * Defaults to "Message…" when this value is empty.
     */
    var inputPlaceholder: String = "",

    /**
     * Message shown in place of the chat input when the conversation has ended.
     * Overridden by server-configured ended message if useConfiguredChatStrings is true.
     * Defaults to "Chat ended" when this value is empty.
     */
    var conversationEndedMessage: String = "",

    /**
     * Message shown when waiting for a human agent to join the conversation.
     * Overridden by server-configured waiting message if useConfiguredChatStrings is true.
     */
    var agentTransferWaitingMessage: String = "Waiting for agent…",

    /**
     * Message shown when waiting for a human agent to join the conversation, and the queue
     * size is known. "{QUEUE_SIZE}" will be replaced with the size of the queue. Overridden by
     * server-configured queue size message if useConfiguredChatStrings is true.
     */
    var agentTransferQueueSizeMessage: String = "Queue Size: {QUEUE_SIZE}",

    /**
     * Message shown when waiting for a human agent to join the conversation, and the user is
     * next in line. Overridden by server-configured queue next message if
     * useConfiguredChatStrings is true.
     */
    var agentTransferQueueNextMessage: String = "You are next in line",

    /**
     * Message shown when a human agent has joined the conversation.
     * Overridden by server-configured joined message if useConfiguredChatStrings is true.
     */
    var agentJoinedMessage: String = "Agent connected",

    /**
     * Message shown when a human agent has left the conversation.
     * Overridden by server-configured left message if useConfiguredChatStrings is true.
     */
    var agentLeftMessage: String = "Agent disconnected",

    /**
     * Customize the colors and other appearance of the chat UI.
     * Overridden by server-configured chat style if useConfiguredStyle is true.
     */
    val chatStyle: ChatStyle = ChatStyle(),

    /**
     * Inline SVG markup for the chat send button. Replaces the default send arrow (including
     * its background) when provided. Overridden by the server-configured value if useConfiguredStyle
     * is true.
     */
    var sendButtonSVG: String? = null,

    /**
     * Inline SVG markup for the send button when it is disabled (e.g. the input is empty).
     * Falls back to sendButtonSVG when not provided. Overridden by the server-configured value
     * if useConfiguredStyle is true.
     */
    var sendButtonDisabledSVG: String? = null,

    /**
     * Hide the title bar in the fragment that the controller creates. The containing view is then
     * responsible for showing a title/app bar with the agent name.
     */
    val hideTitleBar: Boolean = false,

    /**
     * A signed JWT that identifies the end user for this session. When set, the token is
     * forwarded to the server on every chat request for identity resolution. The server
     * extracts the `sub` claim and resolves a persistent EndUser, enabling cross-session
     * memory and conversation history. Must be an RS256-signed JWT with `aud: "sierra.ai"`.
     */
    val userIdentityToken: String? = null,

    /** Whether to show the conversation list UI. Requires userIdentityToken. */
    val enableConversationList: Boolean = false,

    /** Whether to show the conversation list by default when the chat opens. */
    val showConversationListByDefault: Boolean = false,

    /**
     * When true, the variables and secrets supplied via [conversationOptions] are re-sent when an
     * existing conversation is resumed (e.g. when the controller is recreated with new values), so
     * the resumed conversation picks them up. Values are merged per key (later values win); keys not
     * supplied are left unchanged. When false (the default), variables and secrets are only applied
     * when the conversation is first created.
     */
    val updateVariablesAndSecretsOnSessionResume: Boolean = false,

    /** Customization of the Conversation that the controller will create. */
    var conversationOptions: ConversationOptions? = null,

    /** Enable Print Transcript actions to show in Menu Bar and at end of conversation */
    var canPrintTranscript: Boolean = false,
    /** Allow the user to manually end a conversation via a UI */
    var canEndConversation: Boolean = false,
    /**
     * Ask the user to confirm before the conversation ends. The confirmation is shown inline
     * within the chat (covering the transcript and input). Only effective when
     * [canEndConversation] is true.
     */
    var confirmEndConversation: Boolean = false,
    /**
     * Show an end conversation button in the chat footer (above the input) while the user is
     * speaking with a live agent. Only effective when [canEndConversation] is true.
     */
    var footerEndConversationButton: Boolean = false,
    /**
     * If true, a "new chat" button is shown on the conversation view after the conversation
     * has ended. Only effective when [canEndConversation] is true. When the conversation list
     * is enabled, the list view always includes its own button to start a new chat regardless
     * of this setting.
     */
    var canStartNewChat: Boolean = false,

    /**
     * Enable automatic state restoration when navigating away and back.
     *
     * @deprecated This flag is no longer needed. State restoration is now handled automatically
     * based on the Agent's persistence setting. Use [PersistenceMode.MEMORY] or [PersistenceMode.DISK]
     * in [AgentConfig] instead. This flag will be ignored in a future version.
     */
    @Deprecated(
        message = "Use Agent's persistence instead. MEMORY mode provides equivalent behavior.",
        level = DeprecationLevel.WARNING
    )
    var enableAutoStateRestoration: Boolean = false,

    /**
     * Start the chat with messages at the top of the chat frame, allowing the
     * conversation to expand downward until the frame height has been reached,
     * at which point older messages scroll out of view.
     */
    var startAtTop: Boolean = false,

    /**
     * Whether to show a scroll-to-bottom indicator when the user scrolls up in the chat.
     */
    var showScrollToBottom: Boolean = false,

    /**
     * Pin the disclosure text to the top of the chat frame so that it is
     * visible throughout the conversation. This controls where the disclosure
     * sits within the conversation view, and has no effect when
     * disclosurePlacement is CONVERSATION_LIST.
     */
    var pinDisclosure: Boolean = false,

    /**
     * Which view(s) the disclosure text is displayed in. Defaults to CONVERSATION.
     */
    var disclosurePlacement: DisclosurePlacement = DisclosurePlacement.CONVERSATION,

    /**
     * When true, removes the divider (top border) drawn between the chat
     * transcript and the message input area. Defaults to false.
     */
    var removeInputDivider: Boolean = false,

    /**
     * Whether to show timestamps on chat messages. When null and
     * useConfiguredStyle is true, the server-configured value is used.
     */
    var showTimestamps: Boolean? = null,

    /**
     * Whether to show speaker labels (e.g. the agent name) on chat messages.
     * When null and useConfiguredStyle is true, the server-configured value is
     * used.
     */
    var showSpeakerLabels: Boolean? = null,

    /**
     * Whether to show per-message avatars for agents. When enabled, the chat
     * shows avatars next to live agent messages using image URLs provided by
     * the contact center. If agentAvatarURL is also set, that image is shown
     * next to virtual agent messages. When null and useConfiguredStyle is true,
     * the server-configured value is used.
     */
    var showAvatars: Boolean? = null,

    /**
     * HTTPS URL of an image to show next to virtual agent messages when
     * showAvatars is enabled. Values are trimmed and must be 2048 characters or
     * fewer. When null and useConfiguredStyle is true, the server-configured
     * value is used.
     */
    var agentAvatarURL: String? = null,

    /**
     * Controls whether the message label (speaker name and timestamp) is shown
     * above or below chat message bubbles. When DEFAULT and useConfiguredStyle
     * is true, the server-configured value is used.
     */
    var messageLabelPlacement: MessageLabelPlacement = MessageLabelPlacement.DEFAULT,

    /**
     * Explicitly set whether or not to auto-detect locale-specific chat strings and text direction
     * from the conversation locale.
     */
    var autoDetectChatStrings: Boolean? = null,

    /**
     * Explicitly set the text direction of the chat window.
     * - `LTR`: Forces the chat window to use a left-to-right language layout.
     * - `RTL`: Forces the chat window to use a right-to-left language layout.
     * - `AUTO`: Text direction is automatically configured from the conversation locale.
     * When null, automatically determined from locale if auto-detection is active --
     * either via [autoDetectChatStrings] or the server's Agent Studio configuration
     * when [useConfiguredChatStrings] is true. Otherwise falls back to the server
     * value when [useConfiguredChatStrings] is true, or left-to-right.
     */
    var textDirection: TextDirection? = null,

    /** Menu label for the conversation transcript saving item. */
    var saveTranscriptLabel: String = "Save Transcript",

    /** Menu label for the conversation ending item. */
    var endConversationLabel: String = "End Conversation",

    /** Label for the new chat button. */
    var newChatButtonLabel: String = "Start new chat",

    /** Message that will be automatically sent from the user when the conversation starts. */
    var initialUserMessage: String? = null

) : Parcelable {
    companion object {
        // A baseline instance with the hardcoded English defaults, used to detect which
        // fields the caller has actually customized. When locale auto-detect or
        // server-configured chat strings are enabled, any field still equal to its
        // default is omitted so locale defaults or server values can take effect.
        internal val DEFAULTS = AgentChatControllerOptions(name = "")
    }

    @IgnoredOnParcel
    var conversationEventListener: ConversationEventListener? = null

    // SDK-internal options
    //
    // These are configured by SDK coordinators and are not part of the stable public API surface.
    @SierraInternalApi
    @IgnoredOnParcel
    public var onConversationEndedInternal: (() -> Unit)? = null

    internal fun hasCustomGreetingMessage(): Boolean {
        return greetingMessage != DEFAULTS.greetingMessage
    }

    internal fun shouldOmitDefaultChatStrings(): Boolean {
        return autoDetectChatStrings == true || useConfiguredChatStrings
    }

    internal fun shouldUseGreetingMessageAsCustomGreeting(): Boolean {
        if (greetingMessage.isEmpty()) {
            return false
        }
        if (!shouldOmitDefaultChatStrings()) {
            return true
        }
        return hasCustomGreetingMessage()
    }
}

/**
 * Creates a chat controller backed by a WebView.
 *
 * @param agent The Sierra agent to chat with.
 * @param options Long-lived configuration that is safe to reuse across presentations.
 * @param conversationState Optional opaque state token returned by the public Sierra API
 *   identifying a specific conversation to resume. Supply this only on the controller
 *   instance that should resume that conversation; do not retain it on long-lived
 *   configuration, since reusing the same value after the user starts a new
 *   conversation will cause that new conversation to be replaced by the original one.
 */
class AgentChatController(
    internal val agent: Agent,
    private val options: AgentChatControllerOptions,
    private val conversationState: String? = null
) {
    private var connectedFragment: AgentChatFragment? = null

    fun createFragment(): Fragment {
        return AgentChatFragment().apply {
            arguments = Bundle().apply {
                putParcelable(
                    "args",
                    AgentChatFragmentArgs(
                        agentConfig = agent.config,
                        options = options,
                        conversationState = conversationState
                    )
                )
            }
            listener = MainThreadConversationEventListener(options.conversationEventListener)
            controller = this@AgentChatController
        }
    }

    internal fun connectToFragment(fragment: AgentChatFragment) {
        this.connectedFragment = fragment
    }

    internal fun notifyConversationEndedInternal() {
        options.onConversationEndedInternal?.invoke()
    }

    fun printTranscript() {
        this.connectedFragment?.printTranscript()
    }

    fun endConversation() {
        this.connectedFragment?.endConversation()
    }

    fun sendUserAttachment(attachments: List<UserAttachment>) {
        this.connectedFragment?.sendUserAttachment(attachments)
    }

    fun sendUserMessage(message: String, attachments: List<UserAttachment> = emptyList()) {
        this.connectedFragment?.sendUserMessage(message, attachments)
    }

    /**
     * Add tags to the active conversation.
     *
     * The chat WebView must be loaded and a conversation must be active. The callback receives
     * true when the tags were recorded.
     */
    fun addAgentTags(
        tags: List<String>,
        options: AddAgentTagsOptions = AddAgentTagsOptions(),
        callback: (Boolean) -> Unit = {}
    ) {
        this.connectedFragment?.addAgentTags(tags, options, callback) ?: callback(false)
    }

    fun showConversationList() {
        this.connectedFragment?.showConversationList()
    }
}

@Parcelize
private data class AgentChatFragmentArgs(
    val agentConfig: AgentConfig,
    val options: AgentChatControllerOptions,
    val conversationState: String? = null
) : Parcelable

class AgentChatFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var loadingSpinner: ProgressBar
    internal var listener: ConversationEventListener? = null
    internal var controller: AgentChatController? = null
    private var storage: ConversationStorage? = null

    /**
     * Flag used to keep track that of whether the web view successfully loaded or not. We only
     * restore state (and avoid reloading the URL) if the last load was successful.
     * */
    internal var pageLoaded: Boolean = false

    /**
     * Whether the web content has been revealed (spinner hidden, web view faded in). The reveal
     * runs only once per load so repeated readiness signals don't re-trigger the animation.
     */
    private var didRevealContent: Boolean = false

    /**
     * Whether the embed has signalled that it is open. It only starts listening for
     * `appstatuschange` at that point, so earlier dispatches are dropped and the current state is
     * re-sent once it opens.
     */
    private var embedOpened: Boolean = false

    /**
     * The last app status handed to the embed, used to collapse repeated transitions into a single
     * event. Null until the first report, so the first status is always sent even when the fragment
     * starts out backgrounded. Confined to the main thread, like the fragment lifecycle callbacks
     * and [onEmbedOpened] that write it.
     */
    private var reportedAppStatus: AppStatus? = null

    /** Handler/runnable for the fallback reveal of a resumed conversation. */
    private val revealHandler = Handler(Looper.getMainLooper())
    private var revealFallbackRunnable: Runnable? = null
    // Tracks in-flight addAgentTags requests by callback ID, with a monotonic counter for IDs.
    // Both are confined to the main thread (addAgentTags marshals onto it before touching them).
    private val pendingAddAgentTagsCallbacks = mutableMapOf<String, PendingAddAgentTags>()
    private var nextAddAgentTagsCallbackID = 0

    private class PendingAddAgentTags(
        val callback: (Boolean) -> Unit,
        val timeout: Runnable,
    )

    /**
     * Callback for file chooser results from the WebView.
     * Used to pass selected file URIs back to the WebView's file input element.
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * Activity result launcher for the file chooser intent.
     */
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the file chooser launcher before the fragment is started
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris: Array<Uri>? = if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data
                // Handle multiple file selection (stored in clipData)
                val clipData = data?.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    // Handle single file selection (stored in data)
                    data?.data?.let { arrayOf(it) }
                }
            } else {
                // User cancelled - return null to indicate cancellation
                null
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

        // We stash the value of listener and controller in a view model so that when we're recreated we can still
        // get to it and invoke it.
        val viewModel = ViewModelProvider(this)[AgentChatViewModel::class.java]
        if (listener != null) {
            viewModel.listener = listener
        } else {
            listener = viewModel.listener
        }

        if (controller != null) {
            viewModel.controller = controller
        } else {
            controller = viewModel.controller
        }
        controller?.connectToFragment(this)

        // Resolve storage: prefer Agent's storage, fall back to creating one from the
        // parceled config. This handles process death where the ViewModel (and thus the
        // Agent) is gone but the Fragment arguments survive.
        storage = controller?.agent?.getStorage()
        if (storage == null) {
            val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
            if (args != null && args.agentConfig.persistence != PersistenceMode.NONE) {
                storage = ConversationStorage(
                    mode = args.agentConfig.persistence,
                    storageKey = ConversationStorage.storageKeyForToken(args.agentConfig.token),
                    context = if (args.agentConfig.persistence == PersistenceMode.DISK)
                        requireContext().applicationContext else null
                )
                Log.i(TAG, "Created fallback storage after process death")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args == null) {
            Log.w(TAG, "Could not find AgentChatFragment args, will not create web view")
            return View(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        val rootContainer = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            args.options.chatStyle.colors.background?.let { setBackgroundColor(it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(requireContext()) {}
        }
        webView = WebView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Keep the web content hidden until the embed reports it is ready,
            // preventing transient web loading states from flashing onscreen.
            alpha = 0f
            // Set background color to match chat style to avoid white flash while loading
            args.options.chatStyle.colors.background?.let { setBackgroundColor(it) }
        }

        val agentConfig = args.agentConfig
        val chatWebViewClient =
            ChatWebViewClient(this, agentConfig, listener)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = generateUserAgent(requireContext())
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
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Cancel any pending callback
                    this@AgentChatFragment.filePathCallback?.onReceiveValue(null)
                    this@AgentChatFragment.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }

                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot launch file chooser", e)
                        this@AgentChatFragment.filePathCallback?.onReceiveValue(null)
                        this@AgentChatFragment.filePathCallback = null
                        return false
                    }
                    return true
                }
            }
            addJavascriptInterface(
                ChatWebViewInterface(
                    requireContext(),
                    storage,
                    listener,
                    this@AgentChatFragment,
                    this,
                    args.options.conversationOptions
                ),
                "AndroidSDK"
            )
        }
        if (agentConfig.apiHost == AgentAPIHost.LOCAL) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        loadingSpinner = ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }

        rootContainer.addView(webView)
        rootContainer.addView(loadingSpinner)
        return rootContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args == null) {
            Log.w(TAG, "Could not find AgentChatFragment args, will not initialize web view")
            return
        }

        // Preserve the WebView document across configuration changes when the SDK inputs are
        // unchanged. Hosts that rebuild options with new adaptive colors still fall through to
        // loadUrl below because the saved args no longer match the current args.
        if (savedInstanceState != null && savedInstanceState.getBoolean("pageLoaded")) {
            val savedInstanceArgs = savedInstanceState.getParcelable<AgentChatFragmentArgs>("args")
            if (savedInstanceArgs == args) {
                pageLoaded = true
                restoreStorage(savedInstanceState)
                showWebContent()
                webView.restoreState(savedInstanceState)
                return
            }
        }

        val agentConfig = args.agentConfig
        val options = args.options
        // Turn config and options into query parameters that the Android web embed expects.
        val urlBuilder = Uri.parse(agentConfig.url).buildUpon()
        if (agentConfig.target != null && agentConfig.target.isNotEmpty()) {
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
        val brandJSON = JSONObject(brandMap as Map<*, *>).toString()

        urlBuilder.appendQueryParameter("brand", brandJSON)

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
        val chatInterfaceStrings = JSONObject(chatInterfaceStringsMap as Map<*, *>).toString()
        urlBuilder.appendQueryParameter("chatInterfaceStrings", chatInterfaceStrings)

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
            conversationOptions.enableContactCenter.toString()
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
                options.disclosurePlacement.value
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
                options.autoDetectChatStrings.toString()
            )
        }
        options.textDirection?.let {
            urlBuilder.appendQueryParameter("textDirection", it.value)
        }
        if (!options.userIdentityToken.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("userIdentityToken", options.userIdentityToken)
        }
        if (!args.conversationState.isNullOrEmpty()) {
            urlBuilder.appendQueryParameter("state", args.conversationState)
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

        val url = urlBuilder.build().toString()
        webView.loadUrl(url)
    }

    internal fun showWebContent() {
        if (!::webView.isInitialized || !::loadingSpinner.isInitialized) {
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
        if (didRevealContent || revealFallbackRunnable != null) {
            return
        }
        val runnable = Runnable { showWebContent() }
        revealFallbackRunnable = runnable
        revealHandler.postDelayed(runnable, REVEAL_FALLBACK_MS)
    }

    internal fun stopLoadingIndicator() {
        if (!::loadingSpinner.isInitialized) {
            return
        }
        loadingSpinner.visibility = View.GONE
    }

    private fun restoreStorage(savedInstanceState: Bundle) {
        val savedStorage = savedInstanceState.getSerializable("storage") as? HashMap<String, String>
        if (savedStorage != null) {
            savedStorage.forEach { (key, value) ->
                storage?.setItem(key, value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dispatchAppStatusChange(AppStatus.FOREGROUNDED)
    }

    override fun onPause() {
        super.onPause()
        dispatchAppStatusChange(AppStatus.BACKGROUNDED)
    }

    /**
     * Called when the embed reports that it is open. Reports the fragment's current app status,
     * since transitions dispatched while the embed was still loading were dropped.
     */
    internal fun onEmbedOpened() {
        embedOpened = true
        dispatchAppStatusChange(
            if (isResumed) AppStatus.FOREGROUNDED else AppStatus.BACKGROUNDED
        )
    }

    /**
     * Reports a foreground/background transition to the embed, which forwards it to the server so
     * agent code can read `conversation.info.embedAppStatus`. Matches the event the iOS SDK
     * dispatches, including the local timestamp the server uses to discard stale updates.
     */
    private fun dispatchAppStatusChange(status: AppStatus) {
        if (!::webView.isInitialized || !embedOpened) {
            return
        }
        if (reportedAppStatus == status) {
            return
        }
        reportedAppStatus = status
        val localTimestampMs = System.currentTimeMillis()
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('appstatuschange', " +
                "{ detail: { status: '${status.value}', localTimestampMs: $localTimestampMs } }))",
            null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putBoolean("pageLoaded", pageLoaded)
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args != null) {
            outState.putParcelable("args", args)
        }
        val storageData = storage?.getAll()
        if (storageData != null) {
            outState.putSerializable("storage", HashMap(storageData))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        revealFallbackRunnable?.let { revealHandler.removeCallbacks(it) }
        revealFallbackRunnable = null
        pendingAddAgentTagsCallbacks.values.forEach {
            revealHandler.removeCallbacks(it.timeout)
            it.callback(false)
        }
        pendingAddAgentTagsCallbacks.clear()
        didRevealContent = false
        embedOpened = false
        reportedAppStatus = null
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
            null
        )
    }

    fun sendUserMessage(message: String, attachments: List<UserAttachment>) {
        val payload = serializedAttachments(attachments)
        val messageJSON = JSONObject.quote(message).escapeJsLineSeparators()
        webView.evaluateJavascript(
            "sierraAndroid.sendUserMessage($messageJSON, JSON.parse($payload))",
            null
        )
    }

    private fun serializedAttachments(attachments: List<UserAttachment>): String =
        JSONObject.quote(
            JSONArray().apply {
                attachments.forEach { attachment ->
                    put(attachment.toJSONObject())
                }
            }.toString()
        ).escapeJsLineSeparators()

    fun addAgentTags(
        tags: List<String>,
        options: AddAgentTagsOptions,
        callback: (Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            revealHandler.post { addAgentTags(tags, options, callback) }
            return
        }

        if (!::webView.isInitialized) {
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
            null
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
}

private fun generateUserAgent(context: Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion = packageInfo.versionName ?: "0"
    val appName = context.packageName
    val androidVersion = Build.VERSION.RELEASE
    val model = Build.MODEL

    return "Sierra-Android-SDK ($appName/$appVersion $model/$androidVersion) WebView"
}

internal class AgentChatViewModel : ViewModel() {
    internal var listener: ConversationEventListener? = null
    internal var controller: AgentChatController? = null
}

private class ChatWebViewClient(
    private val fragment: AgentChatFragment,
    private val agentConfig: AgentConfig,
    private val listener: ConversationEventListener?,
) : WebViewClient() {
    private var hadError: Boolean = false

    private fun handleMainUrlLoadFailure(view: WebView?) {
        view?.loadUrl("about:blank")
        fragment.pageLoaded = false
        hadError = true
        fragment.stopLoadingIndicator()
        listener?.onConversationInitializationError()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url.toString().startsWith(agentConfig.url) && !hadError) {
            fragment.pageLoaded = true
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
        error: WebResourceError
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Ensures it works in non-Activity contexts
            }

            val context = view?.context ?: return false
            Handler(Looper.getMainLooper()).post {
                context.startActivity(intent)
            }
            return true
        }
        return false
    }
}

private class ChatWebViewInterface(
    private val context: Context,
    private val storage: ConversationStorage?,
    private val listener: ConversationEventListener?,
    private val fragment: AgentChatFragment,
    private val webView: WebView,
    private val conversationOptions: ConversationOptions?
) {
    private val handler = Handler(Looper.getMainLooper())

    private fun handleOnOpen(isNewConversation: Boolean) {
        handler.post {
            fragment.onEmbedOpened()
            if (isNewConversation) {
                // New conversation: the greeting is already rendered, so reveal now.
                fragment.showWebContent()
            } else {
                // Resuming an existing conversation: keep the spinner up until the transcript has
                // rendered (onConversationReady) so we don't flash an empty greeting state. A
                // fallback guards against older embeds that never send onConversationReady.
                fragment.scheduleRevealFallback()
            }
        }
        listener?.onOpen(isNewConversation)
    }

    @JavascriptInterface
    fun onConversationReady() {
        handler.post {
            fragment.showWebContent()
        }
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
        val isSynchronous = dataJSON.optBoolean("isSynchronous")
        val isContactCenter = dataJSON.optBoolean("isContactCenter")
        val dataArrayJSON = dataJSON.optJSONArray("data")
        val dataMap = mutableMapOf<String, String>()
        if (dataArrayJSON != null) {
            for (i in 0 until dataArrayJSON.length()) {
                val item = dataArrayJSON.getJSONObject(i)
                dataMap[item.getString("key")] = item.getString("value")
            }
        }

        val transfer = ConversationTransfer(isSynchronous, isContactCenter, dataMap)
        listener?.onConversationTransfer(transfer)
    }

    private fun createWebPrintJob(webView: WebView) {
        (this.context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { printManager ->
            val jobName = "Chat Transcript"
            val printAdapter = webView.createPrintDocumentAdapter(jobName)

            printManager.print(
                jobName,
                printAdapter,
                PrintAttributes.Builder().build()
            )
        }
    }

    @JavascriptInterface
    fun onPrint(url: String, data: String) {
        var heldWebView: WebView? = null
        fun doWebViewPrint() {
            // Create a WebView object specifically for printing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WebView.startSafeBrowsing(this.context) {}
            }
            val webView = WebView(this.context)
            // Sierra WebView hardening (CWE-693). Inlined adjacent to the WebView construction so
            // SAST tools recognize the defenses; do not factor into a helper.
            webView.settings.allowFileAccess = false
            @Suppress("DEPRECATION")
            webView.settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            webView.settings.allowUniversalAccessFromFileURLs = false
            webView.settings.allowContentAccess = false
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    false

                override fun onPageFinished(view: WebView, url: String) {
                    createWebPrintJob(view)
                    heldWebView = null
                }
            }

            webView.postUrl(url, data.toByteArray())
            // Keep a reference to WebView object until you pass the PrintDocumentAdapter
            // to the PrintManager
            heldWebView = webView
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            doWebViewPrint()
        }
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
        fragment.controller?.notifyConversationEndedInternal()
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
        fragment.onAddAgentTagsResult(callbackId, added)
    }

    @JavascriptInterface
    fun storeValue(key: String, value: String) {
        storage?.setItem(key, value)
    }

    @JavascriptInterface
    fun getStoredValue(key: String): String? {
        return storage?.getItem(key)
    }

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
        val variables = conversationOptions?.variables
        if (!variables.isNullOrEmpty()) {
            memory.put("variables", JSONObject(variables))
        }
        val secrets = conversationOptions?.secrets
        if (!secrets.isNullOrEmpty()) {
            memory.put("secrets", JSONObject(secrets))
        }
        return memory.toString()
    }

    @JavascriptInterface
    fun onSecretExpiry(secretName: String, callbackId: String) {
        listener?.onSecretExpiry(secretName) { result ->
            val jsCode = when (result) {
                is SecretExpiryResult.Success -> {
                    val valueJson = if (result.value != null) {
                        JSONObject.quote(result.value)
                    } else {
                        "null"
                    }
                    "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, $valueJson);"
                }
                is SecretExpiryResult.Error -> {
                    "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, null, ${JSONObject.quote(result.message)});"
                }
            }
            handler.post {
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }

    @JavascriptInterface
    fun onUserIdentityTokenExpiry(callbackId: String) {
        listener?.onUserIdentityTokenExpiry { result ->
            val jsCode = when (result) {
                is SecretExpiryResult.Success -> {
                    val valueJson = if (result.value != null) {
                        JSONObject.quote(result.value)
                    } else {
                        "null"
                    }
                    "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, $valueJson);"
                }
                is SecretExpiryResult.Error -> {
                    "window.__sierraAndroidResolveCallback(${JSONObject.quote(callbackId)}, null, ${JSONObject.quote(result.message)});"
                }
            }
            handler.post {
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }
}

private const val TAG = "AgentChatController"

/**
 * How long to keep the spinner up for a resumed conversation while waiting for
 * onConversationReady. Only reached when the embed does not send that signal (e.g. an older embed
 * build); the normal path reveals as soon as the transcript has rendered.
 */
private const val REVEAL_FALLBACK_MS = 10_000L
private const val ADD_AGENT_TAGS_TIMEOUT_MS = 30_000L

/** Values must stay in sync with the iOS SDK and the server's EmbedBotAppStatus enum. */
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
