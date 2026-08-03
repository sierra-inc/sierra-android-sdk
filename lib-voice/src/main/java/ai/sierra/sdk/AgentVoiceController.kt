// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import ai.sierra.sdk.voice.R
import ai.sierra.sdk.chatkit.voice.EndCallButtonLegacy
import ai.sierra.sdk.chatkit.voice.EndCallButtonPill
import ai.sierra.sdk.chatkit.voice.MuteButtonLegacy
import ai.sierra.sdk.chatkit.voice.MuteButtonPill
import ai.sierra.sdk.chatkit.voice.UnmuteButtonLegacy
import ai.sierra.sdk.chatkit.voice.UnmuteButtonPill
import ai.sierra.sdk.chatkit.voice.VoiceControlButtonLayout
import ai.sierra.sdk.chatkit.voice.VoiceMuteLevelDisplaying
import ai.sierra.sdk.chatkit.voice.VoiceTextComposerView
import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FontRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val DEFAULT_MUTE_PILL_BACKGROUND_COLOR: Int = Color.parseColor("#E7E7E7")
private val DEFAULT_MUTE_PILL_ICON_COLOR: Int = Color.parseColor("#111111")
private val DEFAULT_END_CALL_PILL_BACKGROUND_COLOR: Int = Color.rgb(242, 75, 39)
private val DEFAULT_USER_BUBBLE_COLOR: Int = Color.rgb(52, 138, 210)

public data class AgentAttachment(
    val type: String,
    val data: Map<String, Any?>
)

public interface VoiceCallbacks : AgentEventListener {
    public fun onVoiceEnded()
    public fun onVoiceError(error: Throwable)
    public fun onAgentAttachment(attachments: List<AgentAttachment>) {}
    public fun onSessionInfoReceived(conversationID: String, encryptionKey: String?) {}
    public fun onResumeTokenReceived(token: String) {}
}

private fun Map<String, Any?>.toAgentAttachment(): AgentAttachment? {
    val type = this["type"] as? String ?: return null
    val data = (this["data"] as? Map<*, *>)?.toStringKeyedMap() ?: return null
    return AgentAttachment(type = type, data = data)
}

private fun List<Map<String, Any?>>.toAgentAttachments(): List<AgentAttachment> =
    mapNotNull { it.toAgentAttachment() }

private fun Map<*, *>.toStringKeyedMap(): Map<String, Any?>? {
    val map = mutableMapOf<String, Any?>()
    for ((key, value) in this) {
        val stringKey = key as? String ?: return null
        map[stringKey] = value
    }
    return map
}

@Parcelize
public data class AgentVoiceStyle(
    val backgroundColor: Int = Color.WHITE,
    val titleBarColor: Int = Color.WHITE,
    val titleBarTextColor: Int = Color.BLACK,
    /** Legacy control tint retained for compatibility with existing style initializers. */
    @Deprecated("No longer applied to default controls; pass colors to legacy or pill controls directly.")
    val controlsColor: Int = Color.parseColor("#12304C"),
    val rendererBackgroundColor: Int? = null,
    val muteButtonColor: Int? = null,
    val endConversationButtonColor: Int? = null,
    /**
     * Tint color applied to the mute button glyph and label. Defaults to a dark color for the
     * light default mute pill.
     */
    val muteButtonIconColor: Int = DEFAULT_MUTE_PILL_ICON_COLOR,
    /**
     * Tint color applied to the end conversation button glyph and label.
     */
    val endConversationButtonIconColor: Int = Color.WHITE,
    val conversationDisclosureTextColor: Int = Color.GRAY,
    /** Optional font resource override for the disclosure shown below the controls. */
    @FontRes val conversationDisclosureFontResId: Int? = null,
    /** Font size for the disclosure shown below the controls, in sp. */
    val conversationDisclosureTextSizeSp: Float = 12f,
    /** Transcript bubble colors in the mobile renderer. */
    val messageColors: ChatStyleColors = ChatStyleColors(),
    /** Transcript bubble typography in the mobile renderer. */
    val messageTypography: ChatStyleTypography? = null,
    /** Tint color for the native text composer send button. Defaults to the user message color. */
    @ColorInt val textComposerSendButtonTintColor: Int? = null
) : Parcelable {
    @IgnoredOnParcel
    @Suppress("DEPRECATION")
    internal val legacyControlsColor: Int = controlsColor

    internal fun messageStyleJSONString(): String {
        return JSONObject(ChatStyle(colors = messageColors, typography = messageTypography).toJSON()).toString()
    }
}

@Parcelize
public data class AgentVoiceControllerOptions(
    val name: String,
    var titleBarMessage: String? = null,
    /** Hide the SDK toolbar. The containing view is then responsible for any title/app bar UI. */
    var hideTitleBar: Boolean = false,
    var voiceStyle: AgentVoiceStyle = AgentVoiceStyle(),
    var voicePlaceholderText: String = "How can I help you today?",
    var locale: String = Locale.getDefault().toLanguageTag(),
    var voiceConversationID: String? = null,
    var resumeConversation: Boolean = false,
    var voiceAgentParameters: HashMap<String, String>? = null,
    var disableInterruptions: Boolean = false,
    /** Included in the SVP `open` submessage. Defaults to `true`. */
    var enableText: Boolean = true,
    /** Included in the SVP `open` submessage. Defaults to `true`. */
    var forwardAgentAttachments: Boolean = true,
    /** When true, adds a text input and conversation-event transcript to the native voice surface. */
    var enableTextInput: Boolean = false,
    /** When true with `enableTextInput`, streams live user transcription text in the renderer. */
    var enableLiveTranscription: Boolean = false,
    /** Placeholder shown in the native text composer. */
    var textComposerPlaceholder: String = "Type a reply",
    /** Optional disclosure text shown below the native mute/end controls. */
    var disclosureText: String? = null,
    /** Optional vector/SVG drawable resource override for the mute button. */
    @DrawableRes
    var muteIconResId: Int? = null,
    /** Optional vector/SVG drawable resource override for the muted state. */
    @DrawableRes
    var mutedIconResId: Int? = null,
    /** Optional vector/SVG drawable resource override for the end conversation button. */
    @DrawableRes
    var endConversationIconResId: Int? = null,
    /**
     * Optional vector/SVG drawable resource override for the central waveform placeholder. The
     * drawable is rendered as-provided (its own colors), with no tint applied. It is shown
     * statically, without the speaking-state pulse animation applied to the default waveform.
     */
    @DrawableRes
    var voiceWaveformIconResId: Int? = null,
    /**
     * Optional spacing override for the native mute/end controls row, in dp. Set this when
     * providing custom controls that need spacing different from the SDK defaults.
     */
    var controlsSpacingDp: Int? = null,
    /**
     * Optional equal-width override for the native mute/end controls row. Set this when providing
     * custom controls that should not use the SDK's pill or legacy layout defaults.
     */
    var controlsUseEqualWidths: Boolean? = null
) : Parcelable {
    @Deprecated("Use voiceAgentParameters instead.")
    @IgnoredOnParcel
    public var voiceAgentSecrets: HashMap<String, String>?
        get() = voiceAgentParameters
        set(value) {
            voiceAgentParameters = value
        }

    /** Optional factory for the native mute button component. Called for each voice fragment view. */
    @IgnoredOnParcel
    public var muteButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the native unmute button component. Called for each voice fragment view. */
    @IgnoredOnParcel
    public var unmuteButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the native end-call button component. Called for each voice fragment view. */
    @IgnoredOnParcel
    public var endCallButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the compact mute button shown while the text composer is focused. */
    @IgnoredOnParcel
    public var compactMuteButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the compact unmute button shown while the text composer is focused. */
    @IgnoredOnParcel
    public var compactUnmuteButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the compact end-call button shown while the text composer is focused. */
    @IgnoredOnParcel
    public var compactEndCallButtonProvider: ((Context) -> View)? = null

    /** Optional factory for the native text composer component. */
    @IgnoredOnParcel
    public var textComposerViewProvider: ((Context) -> VoiceTextComposerView)? = null

    /**
     * Optional customizer applied to the OkHttpClient builder that backs the voice WebSocket
     * transport.
     *
     * The default behavior leaves OkHttp's secure transport defaults in place and should be
     * sufficient for all normal and production uses of the SDK.
     *
     * Overrides and any non-default behavior should be considered only in controlled testing
     * situations, such as local development against a server with a self-signed certificate.
     * Production applications should continue using the default behavior.
     */
    @IgnoredOnParcel
    @SierraInternalApi
    public var voiceOkHttpClientCustomizer: ((OkHttpClient.Builder) -> Unit)? = null

    // SDK-internal options
    //
    // These are configured by AgentVoiceChatCoordinator. To opt into unified voice/chat flows, use
    // the coordinator rather than setting these directly.
    @IgnoredOnParcel
    internal var resumeReason: AgentVoiceResumeReason? = null

    @IgnoredOnParcel
    internal var resumeToken: String? = null

    @IgnoredOnParcel
    internal var canSwitchToChat: Boolean = false

    @IgnoredOnParcel
    internal var switchToChatLabel: String = "Continue in chat"

    @IgnoredOnParcel
    internal var onSwitchToChat: ((agentInitiated: Boolean) -> Unit)? = null

    /**
     * When true, tapping End closes the SVP session with the `continue_in_chat` close reason and
     * invokes `onSwitchToChat` instead of `onVoiceEnded`.
     */
    @IgnoredOnParcel
    internal var autoShowChatOnEnd: Boolean = false
}

public fun AgentVoiceControllerOptions.useLegacyVoiceControls(
    context: Context,
    backgroundColor: Int? = null,
    iconColor: Int? = null
) {
    val muteBackground = backgroundColor
        ?: voiceStyle.muteButtonColor
        ?: voiceControlsColorFallback(voiceStyle.legacyControlsColor)
    val endCallBackground = backgroundColor
        ?: voiceStyle.endConversationButtonColor
        ?: voiceControlsColorFallback(voiceStyle.legacyControlsColor)
    val muteIconColor = iconColor ?: Color.WHITE
    val endCallIconColor = iconColor ?: voiceStyle.endConversationButtonIconColor
    muteButtonProvider = { viewContext ->
        MuteButtonLegacy(
            context = viewContext,
            backgroundColor = muteBackground,
            iconColor = muteIconColor,
            muteIconResId = muteIconResId
        )
    }
    unmuteButtonProvider = { viewContext ->
        UnmuteButtonLegacy(
            context = viewContext,
            backgroundColor = muteBackground,
            iconColor = muteIconColor,
            unmuteIconResId = mutedIconResId
        )
    }
    endCallButtonProvider = { viewContext ->
        EndCallButtonLegacy(
            context = viewContext,
            backgroundColor = endCallBackground,
            iconColor = endCallIconColor,
            iconResId = endConversationIconResId
        )
    }
}

public class AgentVoiceController(
    internal val agent: Agent,
    internal val options: AgentVoiceControllerOptions = AgentVoiceControllerOptions(name = "Voice Agent")
) {
    private var connectedFragment: AgentVoiceFragment? = null
    public var conversationEventListener: ConversationEventListener? = null
    public var voiceCallbacks: VoiceCallbacks? = null
        set(value) {
            field = value
            connectedFragment?.voiceCallbacks = value
        }

    @Deprecated("Use AgentVoiceControllerOptions.disableInterruptions.")
    public var disableInterruptions: Boolean
        get() = options.disableInterruptions
        set(value) {
            options.disableInterruptions = value
            connectedFragment?.setDisableInterruptions(value)
        }

    @Suppress("DEPRECATION")
    public constructor(agent: Agent, options: AgentChatControllerOptions) : this(
        agent = agent,
        options = AgentVoiceControllerOptions(
            name = options.name,
            titleBarMessage = options.name,
            voiceStyle = AgentVoiceStyle(
                backgroundColor = options.chatStyle.colors.background ?: Color.WHITE,
                titleBarColor = options.chatStyle.colors.titleBar ?: Color.WHITE,
                titleBarTextColor = options.chatStyle.colors.titleBarText ?: Color.BLACK,
                controlsColor = options.chatStyle.colors.newChatButton
                    ?.takeIf { Color.alpha(it) != 0 }
                    ?: Color.parseColor("#12304C"),
                rendererBackgroundColor = options.chatStyle.colors.background,
                messageColors = options.chatStyle.colors,
                messageTypography = options.chatStyle.typography
            ),
            hideTitleBar = options.hideTitleBar,
            voicePlaceholderText = options.greetingMessage,
            voiceAgentParameters = options.conversationOptions?.secrets?.let { HashMap(it) }
        )
    )

    public fun createFragment(): Fragment {
        return AgentVoiceFragment().apply {
            arguments = Bundle().apply {
                putParcelable("args", AgentVoiceFragmentArgs(agentConfig = agent.config, options = options))
            }
            controller = this@AgentVoiceController
        }
    }

    internal fun connectToFragment(fragment: AgentVoiceFragment) {
        connectedFragment = fragment
        fragment.voiceCallbacks = voiceCallbacks
    }

    public fun interrupt() {
        connectedFragment?.interrupt()
    }

    public fun endConversation(closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL) {
        connectedFragment?.endConversation(closeReason)
    }
}

@Parcelize
private data class AgentVoiceFragmentArgs(
    val agentConfig: AgentConfig,
    val options: AgentVoiceControllerOptions
) : Parcelable

internal class AgentVoiceFragment : Fragment(), VoiceSessionDelegate, MobileRendererDelegate {
    internal var controller: AgentVoiceController? = null
    public var voiceCallbacks: VoiceCallbacks? = null

    private lateinit var parceledArgs: AgentVoiceFragmentArgs
    private val agentConfig: AgentConfig
        get() = controller?.agent?.config ?: parceledArgs.agentConfig
    private val options: AgentVoiceControllerOptions
        get() = controller?.options ?: parceledArgs.options
    private lateinit var rootLayout: LinearLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var placeholderContainer: LinearLayout
    private lateinit var placeholderIcon: ImageView
    private lateinit var placeholderLabel: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorBanner: TextView
    private var muteButton: View? = null
    private var unmuteButton: View? = null
    private var endButton: View? = null
    private var compactMuteButton: View? = null
    private var compactUnmuteButton: View? = null
    private var compactEndButton: View? = null
    private var normalButtonsContainer: LinearLayout? = null
    private var compactButtonsContainer: LinearLayout? = null
    private var textComposerView: VoiceTextComposerView? = null
    private var isTextComposerEditing = false
    private var textComposerKeyboardShowPending = false
    private var textComposerKeyboardShowRetryCount = 0
    private var textComposerKeyboardGeneration = 0
    private var textComposerKeyboardShowRunnable: Runnable? = null
    private var textComposerKeyboardGraceRunnable: Runnable? = null
    private var muteLevelDisplay: VoiceMuteLevelDisplaying? = null
    private var compactMuteLevelDisplay: VoiceMuteLevelDisplaying? = null
    private lateinit var disclosureLabel: TextView
    private var switchToChatMenuItem: MenuItem? = null
    private val controlButtonSpacingDp = 28
    private val controlPillSpacingDp = 8
    private val compactControlPillSpacingDp = 4
    private val controlsHorizontalInsetDp = 16
    private val controlsTopPaddingDp = 16
    private val controlsBottomPaddingDp = 18
    private val controlsBottomPaddingWithDisclosureDp = 4
    private val controlsRowSpacingDp = 12
    private val textComposerKeyboardShowGraceMs = 300L
    private val textComposerKeyboardMaxShowRetries = 10
    private val placeholderWaveformBoxSizeDp = 80
    private val placeholderWaveformIconSizeDp = 40

    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null
    private var rendererView: MobileRendererView? = null
    private var voiceSession: VoiceSessionManager? = null
    private var secretRefreshOrchestrator: SecretRefreshOrchestrator? = null
    private var hasShownFirstAttachment = false
    private var hasReceivedInitialGreeting = false
    private var hasReceivedInitialAudioMessage = false
    private var hasShutdownVoiceSession = false
    private var voiceExitState = VoiceExitState.NONE
    private var rendererFailed = false
    private var lastRenderableAttachmentsSignature: String? = null
    private val deliveredConversationAttachmentSignatures = mutableSetOf<String>()
    private var isMuted = false
    private var latestInputAudioLevel = 0f
    private var latestOutputAudioLevel = 0f
    private var isDisableInterruptions = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialGreetingFallbackRunnable: Runnable? = null
    private val initialGreetingFallbackDelayMs = 2_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parceledArgs = arguments?.let {
            androidx.core.os.BundleCompat.getParcelable(it, "args", AgentVoiceFragmentArgs::class.java)
        } ?: throw IllegalStateException("AgentVoiceFragment args are required")
        isDisableInterruptions = options.disableInterruptions

        val viewModel = ViewModelProvider(this)[AgentVoiceViewModel::class.java]
        if (controller != null) {
            viewModel.controller = controller
        } else {
            controller = viewModel.controller
        }
        controller?.connectToFragment(this)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        AppContextHolder.applicationContext = requireContext().applicationContext

        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(options.voiceStyle.backgroundColor)
        }

        if (!shouldHideTitleBar()) {
            rootLayout.addView(createToolbar())
        }
        rootLayout.addView(createErrorBanner())
        rootLayout.addView(createContentContainer(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        rootLayout.addView(createBottomControls())

        showLoadingState(true)
        return rootLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onError(IllegalStateException("RECORD_AUDIO permission is required before starting voice"))
            return
        }
        startVoiceSession()
    }

    override fun onResume() {
        super.onResume()
        if (isMuted) {
            voiceSession?.pauseListening()
        } else {
            voiceSession?.resumeListening()
        }
    }

    override fun onDestroyView() {
        cancelInitialGreetingFallback()
        cancelTextComposerKeyboardCallbacks()
        shutdownVoiceSessionIfNeeded()
        pulseAnimatorX?.cancel()
        pulseAnimatorX = null
        pulseAnimatorY?.cancel()
        pulseAnimatorY = null
        rendererView?.destroy()
        rendererView = null
        super.onDestroyView()
    }

    internal fun setDisableInterruptions(disabled: Boolean) {
        isDisableInterruptions = disabled
    }

    internal fun interrupt() {
        voiceSession?.interrupt()
    }

    internal fun endConversation(closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL) {
        endConversationForExit(closeReason)
    }

    private fun endConversationForExit(closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL) {
        shutdownVoiceSessionIfNeeded(closeReason)
        deliverVoiceEndedIfNeeded()
    }

    private fun startVoiceSession() {
        val agentParameters = options.voiceAgentParameters ?: hashMapOf()
        hasShutdownVoiceSession = false
        voiceExitState = VoiceExitState.NONE
        isMuted = false
        latestInputAudioLevel = 0f
        latestOutputAudioLevel = 0f
        muteLevelDisplay?.resetLevels()
        updateMuteControl(isMuted = false)
        VoiceSessionService.start(requireContext())
        val session = VoiceSessionManager(
            config = agentConfig,
            conversationId = options.voiceConversationID,
            resumeConversation = options.resumeConversation,
            resumeReason = options.resumeReason,
            resumeToken = options.resumeToken,
            disableInterruptions = isDisableInterruptions,
            localeTag = options.locale,
            agentParameters = agentParameters,
            customizeOkHttpClient = options.voiceOkHttpClientCustomizer,
            enableText = options.enableText,
            forwardAgentAttachments = options.forwardAgentAttachments,
            enableConversationEvents = options.enableTextInput,
            delegate = this
        )
        voiceSession = session
        secretRefreshOrchestrator = SecretRefreshOrchestrator(session, voiceCallbacks)
        if (options.enableTextInput) {
            ensureRendererLoaded()
        }
        session.connect()
        updateUIForState(VoiceSessionManager.State.CONNECTING)
    }

    private fun shutdownVoiceSessionIfNeeded(closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL) {
        if (hasShutdownVoiceSession) {
            return
        }
        hasShutdownVoiceSession = true
        secretRefreshOrchestrator?.cancel()
        secretRefreshOrchestrator = null
        voiceSession?.disconnect(closeReason = closeReason)
        voiceSession = null
        if (isAdded) {
            VoiceSessionService.stop(requireContext())
        }
    }

    private fun deliverVoiceEndedIfNeeded() {
        if (voiceExitState != VoiceExitState.NONE) {
            return
        }
        voiceExitState = VoiceExitState.ENDED
        voiceCallbacks?.onVoiceEnded()
    }

    private fun deliverSwitchToChatIfNeeded(agentInitiated: Boolean) {
        if (voiceExitState != VoiceExitState.NONE) {
            return
        }
        voiceExitState = VoiceExitState.SWITCHED_TO_CHAT
        options.onSwitchToChat?.invoke(agentInitiated)
    }

    private fun handleEndTapped() {
        if (options.autoShowChatOnEnd) {
            switchToChatTapped()
        } else {
            endConversation()
        }
    }

    private fun createToolbar(): Toolbar {
        return Toolbar(requireContext()).apply {
            setBackgroundColor(options.voiceStyle.titleBarColor)
            setTitleTextColor(options.voiceStyle.titleBarTextColor)
            title = options.titleBarMessage?.takeIf { it.isNotBlank() } ?: options.name
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(options.voiceStyle.titleBarTextColor)
            setNavigationOnClickListener { handleEndTapped() }
            if (options.canSwitchToChat) {
                switchToChatMenuItem = menu.add(options.switchToChatLabel).apply {
                    setIcon(R.drawable.sierra_ic_chat_bubble_24)
                    icon?.setTint(options.voiceStyle.titleBarTextColor)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    setOnMenuItemClickListener {
                        switchToChatTapped()
                        true
                    }
                }
            }
        }
    }

    private fun shouldHideTitleBar(): Boolean = options.hideTitleBar && !options.canSwitchToChat

    private fun createErrorBanner(): TextView {
        errorBanner = TextView(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#E94E2A"))
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                40.dp
            )
            setPadding(16.dp, 0, 16.dp, 0)
            visibility = View.GONE
        }
        return errorBanner
    }

    private fun createContentContainer(): FrameLayout {
        contentContainer = FrameLayout(requireContext())
        placeholderContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        loadingIndicator = ProgressBar(requireContext()).apply {
            visibility = View.VISIBLE
        }
        placeholderContainer.addView(loadingIndicator)

        placeholderIcon = ImageView(requireContext()).apply {
            val waveformResId = options.voiceWaveformIconResId
            if (waveformResId != null) {
                setImageResource(waveformResId)
                // Scale custom art to fit so non-40dp assets are not clipped.
                scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                setImageResource(R.drawable.sierra_ic_waveform_40)
                scaleType = ImageView.ScaleType.CENTER
            }
            val inset = ((placeholderWaveformBoxSizeDp - placeholderWaveformIconSizeDp) / 2).dp
            setPadding(inset, inset, inset, inset)
            visibility = View.GONE
        }
        placeholderContainer.addView(
            placeholderIcon,
            LinearLayout.LayoutParams(
                placeholderWaveformBoxSizeDp.dp,
                placeholderWaveformBoxSizeDp.dp
            )
        )

        placeholderLabel = TextView(requireContext()).apply {
            text = options.voicePlaceholderText
            setTextColor(resolvePlaceholderTextColor())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 0)
            visibility = View.GONE
        }
        placeholderContainer.addView(placeholderLabel)

        contentContainer.addView(
            placeholderContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return contentContainer
    }

    private fun createBottomControls(): View {
        val hasDisclosure = !options.disclosureText.isNullOrBlank()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bottomPaddingDp = if (hasDisclosure) controlsBottomPaddingWithDisclosureDp else controlsBottomPaddingDp
            setPadding(
                controlsHorizontalInsetDp.dp,
                controlsTopPaddingDp.dp,
                controlsHorizontalInsetDp.dp,
                bottomPaddingDp.dp
            )
            if (options.enableTextInput) {
                installKeyboardInsetsHandling(this, bottomPaddingDp.dp)
            }
        }
        val buttonsContainer = createNormalControlButtons()
        normalButtonsContainer = buttonsContainer

        val controlsContext = requireContext()
        if (options.enableTextInput) {
            val composer = options.textComposerViewProvider?.invoke(controlsContext)
                ?: VoiceTextComposerView(
                    controlsContext,
                    placeholder = options.textComposerPlaceholder,
                    sendButtonTintColor = defaultTextComposerSendButtonTintColor()
                )
            textComposerView = composer
            composer.onSend = { sendComposerText() }
            composer.editText.setOnFocusChangeListener { _, hasFocus ->
                composer.updateSendButtonVisibility()
                updateTextComposerEditingState(hasFocus)
            }

            val compactControls = createCompactControlButtons()
            compactButtonsContainer = compactControls
            compactControls.visibility = View.GONE

            val editingRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            editingRow.addView(
                composer,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            editingRow.addView(
                compactControls,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                editingRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = controlsRowSpacingDp.dp
                }
            )
        }

        disclosureLabel = TextView(requireContext()).apply {
            text = options.disclosureText.orEmpty()
            setTextColor(options.voiceStyle.conversationDisclosureTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, options.voiceStyle.conversationDisclosureTextSizeSp)
            options.voiceStyle.conversationDisclosureFontResId?.let { fontResId ->
                typeface = ResourcesCompat.getFont(requireContext(), fontResId)
            }
            gravity = Gravity.CENTER
            visibility = if (hasDisclosure) View.VISIBLE else View.GONE
            setPadding(24.dp, 18.dp, 24.dp, 0)
        }
        container.addView(buttonsContainer)
        container.addView(
            disclosureLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        updateTextComposerEditingState(isEditing = false)
        return container
    }

    private fun installKeyboardInsetsHandling(view: View, baseBottomPadding: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { controlsView, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val keyboardBottom = (imeBottom - systemBottom).coerceAtLeast(0)
            val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (isKeyboardVisible && textComposerKeyboardShowPending) {
                textComposerKeyboardShowPending = false
                textComposerKeyboardShowRetryCount = 0
                textComposerKeyboardGraceRunnable?.let { mainHandler.removeCallbacks(it) }
                textComposerKeyboardGraceRunnable = null
            }
            controlsView.setPadding(
                controlsView.paddingLeft,
                controlsView.paddingTop,
                controlsView.paddingRight,
                baseBottomPadding + keyboardBottom
            )
            if (!isKeyboardVisible && isTextComposerEditing && !textComposerKeyboardShowPending) {
                textComposerView?.editText?.clearFocus()
                updateTextComposerEditingState(isEditing = false)
            }
            insets
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun createNormalControlButtons(): LinearLayout {
        val buttonsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val controlsContext = requireContext()
        val mute = options.muteButtonProvider?.invoke(controlsContext) ?: defaultMuteButton()
        val unmute = options.unmuteButtonProvider?.invoke(controlsContext) ?: defaultUnmuteButton()
        val end = options.endCallButtonProvider?.invoke(controlsContext) ?: defaultEndCallButton()
        muteButton = mute
        unmuteButton = unmute
        endButton = end
        muteLevelDisplay = mute as? VoiceMuteLevelDisplaying
        configureButtonsContainer(buttonsContainer, mute, unmute, end, useOptionOverrides = true)
        return buttonsContainer
    }

    private fun createCompactControlButtons(): LinearLayout {
        val buttonsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val controlsContext = requireContext()
        val mute = options.compactMuteButtonProvider?.invoke(controlsContext) ?: defaultCompactMuteButton()
        val unmute = options.compactUnmuteButtonProvider?.invoke(controlsContext) ?: defaultCompactUnmuteButton()
        val end = options.compactEndCallButtonProvider?.invoke(controlsContext) ?: defaultCompactEndCallButton()
        compactMuteButton = mute
        compactUnmuteButton = unmute
        compactEndButton = end
        compactMuteLevelDisplay = mute as? VoiceMuteLevelDisplaying
        configureButtonsContainer(buttonsContainer, mute, unmute, end, useOptionOverrides = false)
        return buttonsContainer
    }

    private fun configureButtonsContainer(
        buttonsContainer: LinearLayout,
        mute: View,
        unmute: View,
        end: View,
        useOptionOverrides: Boolean
    ) {
        mute.setOnClickListener { muteTapped() }
        unmute.setOnClickListener { muteTapped() }
        end.setOnClickListener { handleEndTapped() }

        val usesLegacyControls = usesLegacyControls(mute, unmute, end)
        val usesPillControls =
            mute is MuteButtonPill && unmute is UnmuteButtonPill && end is EndCallButtonPill
        val useDefaultResizablePillLayout =
            useOptionOverrides && usesPillControls && options.controlsUseEqualWidths == null
        val useEqualWidths =
            if (useOptionOverrides) options.controlsUseEqualWidths ?: useDefaultResizablePillLayout else false
        val muteToggleContainer = createMuteToggleContainer(mute, unmute, fillAvailableWidth = useEqualWidths)
        if (useEqualWidths && usesPillControls) {
            for (button in listOf(mute, unmute, end)) {
                button.minimumWidth = 0
            }
        }
        if (useEqualWidths) {
            buttonsContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val controlsSpacing = if (useOptionOverrides) {
            (options.controlsSpacingDp ?: if (usesLegacyControls) controlButtonSpacingDp else controlPillSpacingDp).dp
        } else {
            compactControlPillSpacingDp.dp
        }
        buttonsContainer.addView(muteToggleContainer, controlLayoutParams(muteToggleContainer, useEqualWidths))
        (muteToggleContainer.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = controlsSpacing
        buttonsContainer.addView(
            end,
            controlLayoutParams(end, useEqualWidths)
        )
        updateMuteControl(isMuted = isMuted)
    }

    private fun defaultMuteButton(): View {
        val backgroundColor = options.voiceStyle.muteButtonColor ?: DEFAULT_MUTE_PILL_BACKGROUND_COLOR
        return MuteButtonPill(
            context = requireContext(),
            backgroundColor = backgroundColor,
            iconColor = defaultMuteButtonIconColor(backgroundColor),
            muteIconResId = options.muteIconResId
        )
    }

    private fun defaultUnmuteButton(): View {
        val backgroundColor = options.voiceStyle.muteButtonColor ?: DEFAULT_MUTE_PILL_BACKGROUND_COLOR
        return UnmuteButtonPill(
            context = requireContext(),
            backgroundColor = backgroundColor,
            unmuteIconResId = options.mutedIconResId
        )
    }

    private fun defaultCompactMuteButton(): View {
        val backgroundColor = options.voiceStyle.muteButtonColor ?: DEFAULT_MUTE_PILL_BACKGROUND_COLOR
        return MuteButtonPill(
            context = requireContext(),
            backgroundColor = backgroundColor,
            iconColor = defaultMuteButtonIconColor(backgroundColor),
            muteIconResId = options.muteIconResId,
            layout = VoiceControlButtonLayout.COMPACT
        )
    }

    private fun defaultCompactUnmuteButton(): View {
        val backgroundColor = options.voiceStyle.muteButtonColor ?: DEFAULT_MUTE_PILL_BACKGROUND_COLOR
        return UnmuteButtonPill(
            context = requireContext(),
            backgroundColor = backgroundColor,
            unmuteIconResId = options.mutedIconResId,
            layout = VoiceControlButtonLayout.COMPACT
        )
    }

    private fun defaultCompactEndCallButton(): View {
        return EndCallButtonPill(
            context = requireContext(),
            backgroundColor = options.voiceStyle.endConversationButtonColor ?: DEFAULT_END_CALL_PILL_BACKGROUND_COLOR,
            iconColor = options.voiceStyle.endConversationButtonIconColor,
            iconResId = options.endConversationIconResId,
            layout = VoiceControlButtonLayout.COMPACT
        )
    }

    private fun defaultEndCallButton(): View {
        return EndCallButtonPill(
            context = requireContext(),
            backgroundColor = options.voiceStyle.endConversationButtonColor ?: DEFAULT_END_CALL_PILL_BACKGROUND_COLOR,
            iconColor = options.voiceStyle.endConversationButtonIconColor,
            iconResId = options.endConversationIconResId
        )
    }

    private fun defaultTextComposerSendButtonTintColor(): Int =
        options.voiceStyle.textComposerSendButtonTintColor
            ?: options.voiceStyle.messageColors.userBubble
            ?: DEFAULT_USER_BUBBLE_COLOR

    private fun defaultMuteButtonIconColor(backgroundColor: Int): Int {
        val configuredIconColor = options.voiceStyle.muteButtonIconColor
        return if (
            options.voiceStyle.muteButtonColor != null &&
            configuredIconColor == DEFAULT_MUTE_PILL_ICON_COLOR
        ) {
            contrastingBlackOrWhite(backgroundColor)
        } else {
            configuredIconColor
        }
    }

    private fun usesLegacyControls(mute: View, unmute: View, end: View): Boolean =
        mute is MuteButtonLegacy || unmute is UnmuteButtonLegacy || end is EndCallButtonLegacy

    private fun createMuteToggleContainer(
        mute: View,
        unmute: View,
        fillAvailableWidth: Boolean
    ): FrameLayout {
        return FrameLayout(requireContext()).apply {
            val width = maxPositiveLayoutDimension(mute, unmute) { it.width }
            val height = maxPositiveLayoutDimension(mute, unmute) { it.height }
            val childWidth = if (fillAvailableWidth) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            }
            if (height != null && (fillAvailableWidth || width != null)) {
                layoutParams = LinearLayout.LayoutParams(
                    childWidth,
                    height
                )
            }
            for (button in listOf(mute, unmute)) {
                addView(
                    button,
                    FrameLayout.LayoutParams(
                        childWidth,
                        height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    private fun maxPositiveLayoutDimension(
        first: View,
        second: View,
        getDimension: (ViewGroup.LayoutParams) -> Int
    ): Int? {
        return listOfNotNull(first.layoutParams, second.layoutParams)
            .map(getDimension)
            .filter { it > 0 }
            .maxOrNull()
    }

    private fun controlLayoutParams(view: View, useEqualWidths: Boolean): LinearLayout.LayoutParams {
        if (useEqualWidths) {
            val height =
                view.layoutParams?.height?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
            return LinearLayout.LayoutParams(0, height, 1f)
        }
        val width = view.layoutParams?.width?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val height = view.layoutParams?.height?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(width, height)
    }

    private fun updateMuteControl(isMuted: Boolean) {
        muteButton?.visibility = if (isMuted) View.GONE else View.VISIBLE
        unmuteButton?.visibility = if (isMuted) View.VISIBLE else View.GONE
        compactMuteButton?.visibility = if (isMuted) View.GONE else View.VISIBLE
        compactUnmuteButton?.visibility = if (isMuted) View.VISIBLE else View.GONE
        if (isMuted) {
            muteLevelDisplay?.resetLevels()
            compactMuteLevelDisplay?.resetLevels()
        } else {
            muteLevelDisplay?.setInputLevel(latestInputAudioLevel)
            muteLevelDisplay?.setOutputLevel(latestOutputAudioLevel)
            if (compactButtonsContainer?.visibility == View.VISIBLE) {
                compactMuteLevelDisplay?.setInputLevel(latestInputAudioLevel)
                compactMuteLevelDisplay?.setOutputLevel(latestOutputAudioLevel)
            }
        }
    }

    private fun updateTextComposerEditingState(isEditing: Boolean) {
        if (!options.enableTextInput) {
            return
        }
        isTextComposerEditing = isEditing
        setTextComposerKeyboardVisible(isVisible = isEditing)
        compactButtonsContainer?.visibility = if (isEditing) View.VISIBLE else View.GONE
        normalButtonsContainer?.visibility = if (isEditing) View.GONE else View.VISIBLE
        (textComposerView?.layoutParams as? LinearLayout.LayoutParams)?.let { layoutParams ->
            layoutParams.marginEnd = if (isEditing) 8.dp else 0
            textComposerView?.layoutParams = layoutParams
        }
        disclosureLabel.visibility =
            if (!isEditing && !options.disclosureText.isNullOrBlank()) View.VISIBLE else View.GONE
        if (isEditing && !isMuted) {
            compactMuteLevelDisplay?.setInputLevel(latestInputAudioLevel)
            compactMuteLevelDisplay?.setOutputLevel(latestOutputAudioLevel)
        } else {
            compactMuteLevelDisplay?.resetLevels()
        }
    }

    private fun setTextComposerKeyboardVisible(isVisible: Boolean) {
        val editText = textComposerView?.editText ?: return
        val inputMethodManager =
            editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        cancelTextComposerKeyboardCallbacks()
        val generation = ++textComposerKeyboardGeneration
        if (isVisible) {
            if (!editText.hasFocus()) {
                editText.requestFocus()
            }
            textComposerKeyboardShowPending = true
            textComposerKeyboardShowRetryCount = 0
            val showRunnable = Runnable {
                if (generation != textComposerKeyboardGeneration) {
                    return@Runnable
                }
                textComposerKeyboardShowRunnable = null
                inputMethodManager?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                scheduleTextComposerKeyboardShowRetry(generation, editText, inputMethodManager)
            }
            textComposerKeyboardShowRunnable = showRunnable
            mainHandler.post(showRunnable)
        } else {
            if (editText.hasFocus()) {
                editText.clearFocus()
            }
            inputMethodManager?.hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }

    private fun scheduleTextComposerKeyboardShowRetry(
        generation: Int,
        editText: EditText,
        inputMethodManager: InputMethodManager?
    ) {
        val graceRunnable = Runnable {
            if (generation != textComposerKeyboardGeneration) {
                return@Runnable
            }
            textComposerKeyboardGraceRunnable = null
            if (!isTextComposerEditing || !editText.hasFocus()) {
                textComposerKeyboardShowPending = false
                textComposerKeyboardShowRetryCount = 0
                return@Runnable
            }
            val isKeyboardVisible = ViewCompat.getRootWindowInsets(editText)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (isKeyboardVisible) {
                textComposerKeyboardShowPending = false
                textComposerKeyboardShowRetryCount = 0
                return@Runnable
            }
            if (textComposerKeyboardShowRetryCount >= textComposerKeyboardMaxShowRetries) {
                textComposerKeyboardShowPending = false
                textComposerKeyboardShowRetryCount = 0
                return@Runnable
            }
            textComposerKeyboardShowRetryCount += 1
            inputMethodManager?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            scheduleTextComposerKeyboardShowRetry(generation, editText, inputMethodManager)
        }
        textComposerKeyboardGraceRunnable = graceRunnable
        mainHandler.postDelayed(graceRunnable, textComposerKeyboardShowGraceMs)
    }

    private fun cancelTextComposerKeyboardCallbacks() {
        textComposerKeyboardShowRunnable?.let { mainHandler.removeCallbacks(it) }
        textComposerKeyboardShowRunnable = null
        textComposerKeyboardGraceRunnable?.let { mainHandler.removeCallbacks(it) }
        textComposerKeyboardGraceRunnable = null
        textComposerKeyboardShowPending = false
        textComposerKeyboardShowRetryCount = 0
    }

    private fun sendComposerText() {
        if (!options.enableTextInput) {
            return
        }
        val composer = textComposerView ?: return
        val text = composer.editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return
        }
        if (voiceSession?.sendTextClient(text) != true) {
            Log.d(VOICE_TAG, "AgentVoiceController: text_client send skipped; voice session is not connected")
            return
        }
        composer.editText.text?.clear()
        composer.updateSendButtonVisibility(animated = false)
    }

    private fun ensureRendererLoaded() {
        if (rendererView != null) {
            return
        }
        val renderer = MobileRendererView(
            context = requireContext(),
            agentConfig = agentConfig,
            options = options,
            conversationEventListener = controller?.conversationEventListener,
            delegate = this
        )
        rendererView = renderer
        renderer.visibility = View.GONE
        contentContainer.addView(
            renderer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showLoadingState(visible: Boolean) {
        loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
        placeholderIcon.visibility = if (visible) View.GONE else View.VISIBLE
        placeholderLabel.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun markInitialGreetingReceivedIfNeeded() {
        if (hasReceivedInitialGreeting) {
            return
        }
        hasReceivedInitialGreeting = true
        cancelInitialGreetingFallback()
        showLoadingState(false)
    }

    private fun scheduleInitialGreetingFallbackIfNeeded() {
        if (
            hasReceivedInitialGreeting ||
            hasReceivedInitialAudioMessage ||
            initialGreetingFallbackRunnable != null
        ) {
            return
        }
        val runnable = Runnable { markInitialGreetingReceivedIfNeeded() }
        initialGreetingFallbackRunnable = runnable
        mainHandler.postDelayed(runnable, initialGreetingFallbackDelayMs)
    }

    private fun cancelInitialGreetingFallback() {
        initialGreetingFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        initialGreetingFallbackRunnable = null
    }

    private fun startWaveformAnimation() {
        // A customer-supplied waveform is rendered as-provided, so it is not animated.
        if (options.voiceWaveformIconResId != null) {
            return
        }
        if (placeholderContainer.visibility != View.VISIBLE) {
            return
        }
        if (pulseAnimatorX == null) {
            pulseAnimatorX = ObjectAnimator.ofFloat(placeholderIcon, View.SCALE_X, 1f, 1.06f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            pulseAnimatorX?.start()
            pulseAnimatorY = ObjectAnimator.ofFloat(placeholderIcon, View.SCALE_Y, 1f, 1.06f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            pulseAnimatorY?.start()
        }
    }

    private fun stopWaveformAnimation() {
        pulseAnimatorX?.cancel()
        pulseAnimatorX = null
        pulseAnimatorY?.cancel()
        pulseAnimatorY = null
        placeholderIcon.scaleX = 1f
        placeholderIcon.scaleY = 1f
    }

    private fun updateUIForState(state: VoiceSessionManager.State) {
        when (state) {
            VoiceSessionManager.State.CONNECTING -> {
                setControlButtonsEnabled(true)
                showLoadingState(!hasReceivedInitialGreeting)
                cancelInitialGreetingFallback()
                stopWaveformAnimation()
            }
            VoiceSessionManager.State.LISTENING -> {
                showLoadingState(!hasReceivedInitialGreeting)
                scheduleInitialGreetingFallbackIfNeeded()
                setControlButtonsEnabled(true)
                stopWaveformAnimation()
            }
            VoiceSessionManager.State.SPEAKING -> {
                showLoadingState(!hasReceivedInitialGreeting)
                cancelInitialGreetingFallback()
                setControlButtonsEnabled(true)
                startWaveformAnimation()
            }
            VoiceSessionManager.State.ENDED -> {
                showLoadingState(false)
                cancelInitialGreetingFallback()
                latestInputAudioLevel = 0f
                latestOutputAudioLevel = 0f
                stopWaveformAnimation()
                muteLevelDisplay?.resetLevels()
                compactMuteLevelDisplay?.resetLevels()
                setControlButtonsEnabled(false)
            }
        }
    }

    private fun setControlButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        for (button in listOfNotNull(muteButton, unmuteButton, endButton, compactMuteButton, compactUnmuteButton, compactEndButton)) {
            button.isEnabled = enabled
            button.alpha = alpha
        }
        textComposerView?.let { composer ->
            val composerEnabled = enabled && options.enableTextInput
            composer.editText.isEnabled = composerEnabled
            composer.sendButton.isEnabled = composerEnabled
            composer.alpha = if (composerEnabled) 1f else 0.5f
            if (!composerEnabled) {
                updateTextComposerEditingState(isEditing = false)
            }
        }
        switchToChatMenuItem?.isEnabled = enabled
        switchToChatMenuItem?.icon?.alpha = if (enabled) 255 else 128
    }

    private fun showErrorState(message: String) {
        latestInputAudioLevel = 0f
        latestOutputAudioLevel = 0f
        stopWaveformAnimation()
        muteLevelDisplay?.resetLevels()
        compactMuteLevelDisplay?.resetLevels()
        shutdownVoiceSessionIfNeeded()
        errorBanner.text = message
        errorBanner.visibility = View.VISIBLE

        if (hasShownFirstAttachment) {
            rendererView?.visibility = View.VISIBLE
            placeholderContainer.visibility = View.GONE
        } else {
            rendererView?.visibility = View.GONE
            placeholderContainer.visibility = View.GONE
        }

        loadingIndicator.visibility = View.GONE
        setControlButtonsEnabled(false)
    }

    private fun muteTapped() {
        isMuted = !isMuted
        if (isMuted) {
            voiceSession?.pauseListening()
            latestInputAudioLevel = 0f
            muteLevelDisplay?.setInputLevel(0f)
            compactMuteLevelDisplay?.setInputLevel(0f)
        } else {
            voiceSession?.resumeListening()
        }
        updateMuteControl(isMuted)
    }

    private fun switchToChatTapped() {
        shutdownVoiceSessionIfNeeded(AgentVoiceCloseReason.CONTINUE_IN_CHAT)
        deliverSwitchToChatIfNeeded(agentInitiated = false)
    }

    private fun userFacingErrorMessage(): String {
        return "Voice connection failed: Please check your credentials or try again later"
    }

    private fun isExternalAudioInterruptionError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val className = current::class.java.name.lowercase()
            val message = current.message?.lowercase().orEmpty()
            val isAudioRelated = className.contains("audio") || message.contains("audio")
            if (
                className.contains("audiofocus") ||
                message.contains("audio focus") ||
                message.contains("audiofocus") ||
                (isAudioRelated && message.contains("interruption")) ||
                message.contains("cannot interrupt others")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    override fun onReceiveCredentials(conversationID: String, encryptionKey: String?) {
        voiceCallbacks?.onSessionInfoReceived(conversationID, encryptionKey)
    }

    override fun onReceiveResumeToken(token: String) {
        voiceCallbacks?.onResumeTokenReceived(token)
    }

    override fun onReceiveInitialAudio() {
        hasReceivedInitialAudioMessage = true
        cancelInitialGreetingFallback()
    }

    override fun onStartInitialAudioPlayback() {
        markInitialGreetingReceivedIfNeeded()
    }

    override fun onUpdateInputAudioLevel(level: Float) {
        if (isMuted) {
            return
        }
        latestInputAudioLevel = level
        muteLevelDisplay?.setInputLevel(level)
        if (compactButtonsContainer?.visibility == View.VISIBLE) {
            compactMuteLevelDisplay?.setInputLevel(level)
        }
    }

    override fun onUpdateOutputAudioLevel(level: Float) {
        latestOutputAudioLevel = level
        muteLevelDisplay?.setOutputLevel(level)
        if (compactButtonsContainer?.visibility == View.VISIBLE) {
            compactMuteLevelDisplay?.setOutputLevel(level)
        }
    }

    override fun onReceiveConversationEvent(event: AgentVoiceConversationEvent) {
        mainHandler.post {
            if (!options.enableTextInput) {
                return@post
            }
            deliverConversationEventAttachmentsIfNeeded(event)
            if (rendererFailed) {
                return@post
            }
            ensureRendererLoaded()
            revealRendererContentIfNeeded()
            markInitialGreetingReceivedIfNeeded()
            rendererView?.pushConversationEvent(event)
        }
    }

    override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) {
        val (secretRefreshAttachments, renderableAttachments) = attachments.partition {
            SecretRefreshOrchestrator.isSecretRefreshAttachment(it)
        }
        if (secretRefreshAttachments.isNotEmpty()) {
            val orchestrator = secretRefreshOrchestrator
            if (orchestrator != null) {
                orchestrator.setCallbacks(voiceCallbacks)
                secretRefreshAttachments.forEach { orchestrator.handle(it) }
            } else {
                Log.w(VOICE_TAG, "Received secret_refresh attachment but no orchestrator is registered")
            }
        }

        if (renderableAttachments.isEmpty()) {
            return
        }

        if (options.enableTextInput) {
            Log.d(VOICE_TAG, "AgentVoiceController: skipping attachments_server render because enableTextInput uses conversation events")
            return
        }

        val signature = canonicalizeForSignature(renderableAttachments)
        if (signature == lastRenderableAttachmentsSignature) {
            return
        }
        lastRenderableAttachmentsSignature = signature

        val agentAttachments = renderableAttachments.toAgentAttachments()

        if (agentAttachments.isNotEmpty()) {
            voiceCallbacks?.onAgentAttachment(agentAttachments)
        }

        if (rendererFailed) {
            return
        }
        ensureRendererLoaded()
        if (rendererFailed) {
            return
        }
        revealRendererContentIfNeeded()
        rendererView?.pushAttachments(renderableAttachments)
    }

    private fun deliverConversationEventAttachmentsIfNeeded(event: AgentVoiceConversationEvent) {
        if (event.attachments.isEmpty()) {
            return
        }
        val signature = "${event.messageId}:${canonicalizeForSignature(event.attachments)}"
        if (!deliveredConversationAttachmentSignatures.add(signature)) {
            return
        }
        val (secretRefreshAttachments, renderableAttachments) = event.attachments.partition {
            SecretRefreshOrchestrator.isSecretRefreshAttachment(it)
        }
        if (secretRefreshAttachments.isNotEmpty()) {
            val orchestrator = secretRefreshOrchestrator
            if (orchestrator != null) {
                orchestrator.setCallbacks(voiceCallbacks)
                secretRefreshAttachments.forEach { orchestrator.handle(it) }
            } else {
                Log.w(VOICE_TAG, "Received secret_refresh attachment but no orchestrator is registered")
            }
        }
        val agentAttachments = renderableAttachments.toAgentAttachments()
        if (agentAttachments.isNotEmpty()) {
            voiceCallbacks?.onAgentAttachment(agentAttachments)
        }
    }

    private fun revealRendererContentIfNeeded() {
        if (hasShownFirstAttachment) {
            return
        }
        hasShownFirstAttachment = true
        placeholderContainer.visibility = View.GONE
        stopWaveformAnimation()
        rendererView?.visibility = View.VISIBLE
    }

    override fun onChangeState(state: VoiceSessionManager.State) {
        mainHandler.post {
            updateUIForState(state)
        }
    }

    override fun onError(error: Throwable) {
        Log.e(VOICE_TAG, "Voice session error", error)
        mainHandler.post {
            if (isExternalAudioInterruptionError(error)) {
                endConversationForExit()
                return@post
            }
            showErrorState(userFacingErrorMessage())
            voiceCallbacks?.onVoiceError(error)
        }
    }

    override fun onEnd() {
        mainHandler.post {
            updateUIForState(VoiceSessionManager.State.ENDED)
            shutdownVoiceSessionIfNeeded()
            deliverVoiceEndedIfNeeded()
        }
    }

    override fun onContinueInChat() {
        mainHandler.post {
            updateUIForState(VoiceSessionManager.State.ENDED)
            // The server already closed the SVP session for the handoff; mark it shut down and
            // deliver the switch-to-chat exit so the coordinator presents chat. The exit-state
            // guard prevents double-firing if the user had also tapped the chat-bubble action.
            shutdownVoiceSessionIfNeeded()
            deliverSwitchToChatIfNeeded(agentInitiated = true)
        }
    }

    override fun onSVPClientEvent(text: String, attachments: List<Map<String, Any?>>) {
        if (text.isNotEmpty()) {
            voiceSession?.sendTextClient(text)
        }
        if (attachments.isNotEmpty()) {
            voiceSession?.sendAttachmentsClient(attachments)
        }
    }

    override fun onMobileRendererError(error: Throwable) {
        Log.e(VOICE_TAG, "Renderer error", error)
        rendererFailed = true
        rendererView?.visibility = View.GONE
        placeholderContainer.visibility = View.VISIBLE
    }

    override fun onLinkClick(url: Uri) {
        if (voiceCallbacks?.onLinkClick(url) == true) {
            return
        }
        // Prefer VoiceCallbacks now that it inherits AgentEventListener, but keep the
        // conversationEventListener fallback for older direct-voice integrations that handled
        // mobile-renderer links there. If both are different objects and the voice callback returns
        // false, both may observe the URL before the SDK falls back to Intent.ACTION_VIEW.
        val conversationEventListener = controller?.conversationEventListener
        if (conversationEventListener !== voiceCallbacks && conversationEventListener?.onLinkClick(url) == true) {
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            requireContext().startActivity(intent)
        } catch (e: Throwable) {
            Log.w(VOICE_TAG, "Failed to start activity for external URL", e)
        }
    }

    private fun canonicalizeForSignature(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries = value.entries
                    .filter { it.key is String }
                    .sortedBy { it.key as String }
                    .joinToString(",") { entry ->
                        val key = entry.key as String
                        "${JSONObject.quote(key)}:${canonicalizeForSignature(entry.value)}"
                    }
                "{$entries}"
            }
            is Iterable<*> -> {
                val items = value.joinToString(",") { item -> canonicalizeForSignature(item) }
                "[$items]"
            }
            is Array<*> -> {
                val items = value.joinToString(",") { item -> canonicalizeForSignature(item) }
                "[$items]"
            }
            is JSONArray -> {
                val items = (0 until value.length()).joinToString(",") { index ->
                    canonicalizeForSignature(value.opt(index))
                }
                "[$items]"
            }
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                val entries = keys.joinToString(",") { key ->
                    "${JSONObject.quote(key)}:${canonicalizeForSignature(value.opt(key))}"
                }
                "{$entries}"
            }
            else -> JSONObject.quote(value.toString())
        }
    }
}

private enum class VoiceExitState {
    NONE,
    ENDED,
    SWITCHED_TO_CHAT,
}

internal class AgentVoiceViewModel : ViewModel() {
    internal var controller: AgentVoiceController? = null
}

private fun Fragment.resolvePlaceholderTextColor(): Int {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val alpha = 184
    return if (uiMode == Configuration.UI_MODE_NIGHT_YES) {
        Color.argb(alpha, 238, 238, 238)
    } else {
        Color.argb(alpha, 17, 17, 17)
    }
}

private fun voiceControlsColorFallback(color: Int): Int =
    color.takeIf { Color.alpha(it) != 0 } ?: Color.parseColor("#12304C")

private fun contrastingBlackOrWhite(color: Int): Int {
    val luminance = relativeLuminance(color)
    val whiteContrast = (1.0 + 0.05) / (luminance + 0.05)
    val blackContrast = (luminance + 0.05) / 0.05
    return if (whiteContrast > blackContrast) Color.WHITE else DEFAULT_MUTE_PILL_ICON_COLOR
}

private fun relativeLuminance(color: Int): Double {
    fun linearized(component: Int): Double {
        val value = component / 255.0
        return if (value <= 0.03928) {
            value / 12.92
        } else {
            Math.pow((value + 0.055) / 1.055, 2.4)
        }
    }
    return 0.2126 * linearized(Color.red(color)) +
        0.7152 * linearized(Color.green(color)) +
        0.0722 * linearized(Color.blue(color))
}

private val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()
