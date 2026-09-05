// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
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

/** Controls when an enabled end-conversation confirmation is shown. */
enum class EndConversationConfirmationMode(val value: String) {
    /** Confirm whenever the user ends a conversation. */
    ALWAYS("always"),
    /** Confirm only while waiting for or speaking with a human agent. */
    LIVE_CHAT("liveChat"),
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
     * Show an end conversation button in the input area, below the transcript divider, while the
     * user is waiting for or speaking with a live agent. While waiting, the agent's transfer
     * waiting message takes precedence when the agent has it enabled. Only effective when
     * [canEndConversation] is true.
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
     * Whether chat interface strings (button labels, tooltips, etc.) and text direction are
     * automatically localized from the conversation locale at the start of the conversation.
     * When null and useConfiguredChatStrings is true, the server-configured value is used.
     */
    var autoDetectChatStrings: Boolean? = null,

    /**
     * Whether chat interface strings (button labels, tooltips, etc.) and text direction are
     * automatically updated when the agent changes the conversation locale mid-conversation.
     * When null and useConfiguredChatStrings is true, the server-configured value is used.
     */
    var autoUpdateChatStrings: Boolean? = null,

    /**
     * Explicitly set the text direction of the chat window, taking precedence over
     * autoDetectChatStrings and autoUpdateChatStrings:
     * - `LTR`: Forces the chat window to use a left-to-right language layout.
     * - `RTL`: Forces the chat window to use a right-to-left language layout.
     * - `AUTO`: Text direction automatically follows the conversation locale.
     * When null, text direction follows the conversation locale if autoDetectChatStrings is
     * active or once autoUpdateChatStrings applies a mid-conversation locale change.
     * Otherwise, follows the Agent Studio "Text direction" setting if
     * useConfiguredChatStrings is true; otherwise defaults to left-to-right.
     */
    var textDirection: TextDirection? = null,

    /** Menu label for the conversation transcript saving item. */
    var saveTranscriptLabel: String = "Save Transcript",

    /** Menu label for the conversation ending item. */
    var endConversationLabel: String = "End Conversation",

    /** Label for the new chat button. */
    var newChatButtonLabel: String = "Start new chat",

    /** Message that will be automatically sent from the user when the conversation starts. */
    var initialUserMessage: String? = null,

    /** Controls when confirmation is shown when [confirmEndConversation] is true. */
    var confirmEndConversationMode: EndConversationConfirmationMode =
        EndConversationConfirmationMode.ALWAYS,

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
        return autoDetectChatStrings == true || autoUpdateChatStrings == true ||
            useConfiguredChatStrings
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
 * @param conversationID Optional external conversation ID associated with an existing
 *   conversation. Requires [AgentChatControllerOptions.userIdentityToken] for the user associated
 *   with the conversation. Do not provide both [conversationState] and [conversationID].
 */
class AgentChatController(
    internal val agent: Agent,
    private val options: AgentChatControllerOptions,
    private val conversationState: String? = null,
    private val conversationID: String? = null,
) {
    private var connectedView: AgentChatView? = null

    constructor(
        agent: Agent,
        options: AgentChatControllerOptions,
        conversationState: String?,
    ) : this(agent, options, conversationState, null)

    fun createFragment(): Fragment {
        return AgentChatFragment().apply {
            arguments = Bundle().apply {
                putParcelable(
                    "args",
                    AgentChatFragmentArgs(
                        agentConfig = agent.config,
                        options = options,
                        conversationState = conversationState,
                    )
                )
                putString(ARG_CONVERSATION_ID, conversationID)
            }
            listener = MainThreadConversationEventListener(options.conversationEventListener)
            controller = this@AgentChatController
        }
    }

    /**
     * Creates a plain Android View that hosts the agent chat without requiring a FragmentManager.
     *
     * Hosts that support attachments must launch [fileChooserLauncher] and pass its result to
     * [AgentChatView.onFileChooserResult]. The view observes the lifecycle owner installed on its
     * view tree and disposes itself when that owner is destroyed.
     *
     * [viewId] must be unique within the host's view hierarchy and stable across recreation. The
     * default is suitable only when the hierarchy contains one [AgentChatView]. Hosts displaying
     * multiple chat views must provide a different stable resource ID for each view or saved state
     * may be restored into the wrong conversation.
     */
    @JvmOverloads
    fun createView(
        context: Context,
        @IdRes viewId: Int = R.id.sierra_agent_chat_view,
        fileChooserLauncher: ((Intent) -> Unit)? = null,
    ): AgentChatView {
        return AgentChatView(
            context = context,
            agentConfig = agent.config,
            options = options,
            conversationState = conversationState,
            conversationID = conversationID,
            listener = MainThreadConversationEventListener(options.conversationEventListener),
            storage = agent.getStorage(),
            fileChooserLauncher = fileChooserLauncher,
            onConversationEndedInternal = ::notifyConversationEndedInternal,
            onDispose = ::disconnectFromView,
            viewId = viewId,
        ).also {
            connectToView(it)
            it.initializeWhenAttached()
        }
    }

    internal fun connectToView(view: AgentChatView) {
        connectedView = view
    }

    internal fun disconnectFromView(view: AgentChatView) {
        if (connectedView === view) {
            connectedView = null
        }
    }

    internal fun notifyConversationEndedInternal() {
        options.onConversationEndedInternal?.invoke()
    }

    fun printTranscript() {
        connectedView?.printTranscript()
    }

    /**
     * Ends the current conversation, if any. When confirmation is enabled,
     * [AgentChatControllerOptions.confirmEndConversationMode] controls whether this call asks the
     * user to confirm. [EndConversationConfirmationMode.LIVE_CHAT] confirms only while waiting for
     * or connected to a live agent; other calls end without confirmation.
     */
    fun endConversation() {
        connectedView?.endConversation()
    }

    fun sendUserAttachment(attachments: List<UserAttachment>) {
        connectedView?.sendUserAttachment(attachments)
    }

    fun sendUserMessage(message: String, attachments: List<UserAttachment> = emptyList()) {
        connectedView?.sendUserMessage(message, attachments)
    }

    /**
     * Add tags to the active conversation.
     *
     * The chat WebView must be initialized and a conversation must be active. Calls made before
     * the view is initialized complete with false. Otherwise, the callback receives true when the
     * tags were recorded.
     */
    fun addAgentTags(
        tags: List<String>,
        options: AddAgentTagsOptions = AddAgentTagsOptions(),
        callback: (Boolean) -> Unit = {}
    ) {
        connectedView?.addAgentTags(tags, options, callback) ?: callback(false)
    }

    fun showConversationList() {
        connectedView?.showConversationList()
    }
}

@Parcelize
// AgentChatView also uses this state type. The Fragment-era name must remain stable because Android
// persists Parcelable class names, and renaming it would break state saved by older SDK versions.
internal data class AgentChatFragmentArgs(
    val agentConfig: AgentConfig,
    val options: AgentChatControllerOptions,
    val conversationState: String? = null,
) : Parcelable

class AgentChatFragment : Fragment() {
    private var chatView: AgentChatView? = null
    /**
     * Activity result launcher for the file chooser intent.
     */
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    internal var listener: ConversationEventListener? = null
    internal var controller: AgentChatController? = null
    private var storage: ConversationStorage? = null

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
            chatView?.onFileChooserResult(uris)
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

        return AgentChatView(
            context = requireContext(),
            agentConfig = args.agentConfig,
            options = args.options,
            conversationState = args.conversationState,
            conversationID = arguments?.getString(ARG_CONVERSATION_ID),
            listener = listener,
            storage = storage,
            fileChooserLauncher = fileChooserLauncher::launch,
            onConversationEndedInternal = controller?.let { controller ->
                { controller.notifyConversationEndedInternal() }
            },
            onDispose = controller?.let { controller ->
                { view -> controller.disconnectFromView(view) }
            },
            viewId = View.NO_ID,
        ).also {
            chatView = it
            controller?.connectToView(it)
            it.initialize(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        chatView?.onHostResume()
    }

    override fun onPause() {
        super.onPause()
        chatView?.onHostPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        chatView?.saveState(outState)
    }

    override fun onDestroyView() {
        chatView?.dispose()
        chatView = null
        super.onDestroyView()
    }

    fun printTranscript() {
        chatView?.printTranscript()
    }

    /**
     * Ends the current conversation using the confirmation policy configured by the owning
     * [AgentChatController]. [EndConversationConfirmationMode.LIVE_CHAT] confirms only while
     * waiting for or connected to a live agent.
     */
    fun endConversation() {
        chatView?.endConversation()
    }

    fun sendUserAttachment(attachments: List<UserAttachment>) {
        chatView?.sendUserAttachment(attachments)
    }

    fun sendUserMessage(message: String, attachments: List<UserAttachment>) {
        chatView?.sendUserMessage(message, attachments)
    }

    fun addAgentTags(
        tags: List<String>,
        options: AddAgentTagsOptions,
        callback: (Boolean) -> Unit
    ) {
        chatView?.addAgentTags(tags, options, callback) ?: callback(false)
    }

    fun showConversationList() {
        chatView?.showConversationList()
    }
}

internal class AgentChatViewModel : ViewModel() {
    internal var listener: ConversationEventListener? = null
    internal var controller: AgentChatController? = null
}

private const val TAG = "AgentChatController"
private const val ARG_CONVERSATION_ID = "conversationID"
