// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import ai.sierra.sdk.voice.R
import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

public data class AgentAttachment(
    val type: String,
    val data: Map<String, Any?>
)

public interface VoiceCallbacks {
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
    val controlsColor: Int = Color.parseColor("#12304C"),
    val rendererBackgroundColor: Int? = null
) : Parcelable

@Parcelize
public data class AgentVoiceControllerOptions(
    val name: String,
    var titleBarMessage: String? = null,
    var voiceStyle: AgentVoiceStyle = AgentVoiceStyle(),
    var voicePlaceholderText: String = "How can I help you today?",
    var locale: String = Locale.getDefault().toLanguageTag(),
    var voiceConversationID: String? = null,
    var resumeConversation: Boolean = false,
    var voiceAgentParameters: HashMap<String, String>? = null,
    var disableInterruptions: Boolean = false,
    var allowInsecureLocalConnections: Boolean = false,
    /** Included in the SVP `open` submessage. Defaults to `true`. */
    var enableText: Boolean = true,
    /** Included in the SVP `open` submessage. Defaults to `true`. */
    var forwardAgentAttachments: Boolean = true
) : Parcelable {
    @Deprecated("Use voiceAgentParameters instead.")
    @IgnoredOnParcel
    public var voiceAgentSecrets: HashMap<String, String>?
        get() = voiceAgentParameters
        set(value) {
            voiceAgentParameters = value
        }

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
    internal var onSwitchToChat: (() -> Unit)? = null
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
                rendererBackgroundColor = options.chatStyle.colors.background
            ),
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
    private lateinit var muteButton: ImageView
    private lateinit var endButton: ImageView
    private var switchToChatMenuItem: MenuItem? = null
    private val controlButtonSizeDp = 64
    private val controlIconMaxSizeDp = 32
    private val controlButtonSpacingDp = 28
    private val controlsTopPaddingDp = 16
    private val controlsBottomPaddingDp = 18
    private val placeholderWaveformBoxSizeDp = 80
    private val placeholderWaveformIconSizeDp = 40

    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null
    private var rendererView: MobileRendererView? = null
    private var voiceSession: VoiceSessionManager? = null
    private var hasShownFirstAttachment = false
    private var hasReceivedInitialGreeting = false
    private var hasShutdownVoiceSession = false
    private var voiceExitState = VoiceExitState.NONE
    private var rendererFailed = false
    private var lastRenderableAttachmentsSignature: String? = null
    private var isMuted = false
    private var isDisableInterruptions = false

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

        rootLayout.addView(createToolbar())
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
        voiceSession?.resumeListening()
    }

    override fun onDestroyView() {
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
        VoiceSessionService.start(requireContext())
        voiceSession = VoiceSessionManager(
            config = agentConfig,
            conversationId = options.voiceConversationID,
            resumeConversation = options.resumeConversation,
            resumeReason = options.resumeReason,
            resumeToken = options.resumeToken,
            disableInterruptions = isDisableInterruptions,
            localeTag = options.locale,
            agentParameters = agentParameters,
            allowInsecureLocalConnections = options.allowInsecureLocalConnections,
            enableText = options.enableText,
            forwardAgentAttachments = options.forwardAgentAttachments,
            delegate = this
        ).also { it.connect() }
        updateUIForState(VoiceSessionManager.State.CONNECTING)
    }

    private fun shutdownVoiceSessionIfNeeded(closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL) {
        if (hasShutdownVoiceSession) {
            return
        }
        hasShutdownVoiceSession = true
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

    private fun deliverSwitchToChatIfNeeded() {
        if (voiceExitState != VoiceExitState.NONE) {
            return
        }
        voiceExitState = VoiceExitState.SWITCHED_TO_CHAT
        options.onSwitchToChat?.invoke()
    }

    private fun createToolbar(): Toolbar {
        return Toolbar(requireContext()).apply {
            setBackgroundColor(options.voiceStyle.titleBarColor)
            setTitleTextColor(options.voiceStyle.titleBarTextColor)
            title = options.titleBarMessage?.takeIf { it.isNotBlank() } ?: options.name
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(options.voiceStyle.titleBarTextColor)
            setNavigationOnClickListener { endConversation() }
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
            setImageResource(R.drawable.sierra_ic_waveform_40)
            scaleType = ImageView.ScaleType.CENTER
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
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, controlsTopPaddingDp.dp, 0, controlsBottomPaddingDp.dp)
        }
        muteButton = createCircleButton(R.drawable.sierra_ic_mic_24).apply {
            contentDescription = "Mute microphone"
        }
        endButton = createCircleButton(R.drawable.sierra_ic_close_24).apply {
            contentDescription = "Close conversation"
        }

        muteButton.setOnClickListener { muteTapped() }
        endButton.setOnClickListener { endConversation() }

        container.addView(muteButton, LinearLayout.LayoutParams(controlButtonSizeDp.dp, controlButtonSizeDp.dp).apply {
            marginEnd = controlButtonSpacingDp.dp
        })
        container.addView(endButton, LinearLayout.LayoutParams(controlButtonSizeDp.dp, controlButtonSizeDp.dp))
        return container
    }

    private fun createCircleButton(iconRes: Int): ImageView {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        // Some host apps pass transparent color ints unintentionally (e.g. Color(0)).
        // Fall back to the SDK default so controls remain visible.
        val controlsColor = options.voiceStyle.controlsColor
            .takeIf { Color.alpha(it) != 0 }
            ?: Color.parseColor("#12304C")
        bg.setColor(controlsColor)
        return ImageView(requireContext()).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            val iconInset = ((controlButtonSizeDp - controlIconMaxSizeDp) / 2).dp
            setPadding(iconInset, iconInset, iconInset, iconInset)
            background = bg
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            contentDescription = ""
        }
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
        showLoadingState(false)
    }

    private fun startWaveformAnimation() {
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
                stopWaveformAnimation()
            }
            VoiceSessionManager.State.LISTENING -> {
                markInitialGreetingReceivedIfNeeded()
                setControlButtonsEnabled(true)
                stopWaveformAnimation()
            }
            VoiceSessionManager.State.SPEAKING -> {
                markInitialGreetingReceivedIfNeeded()
                setControlButtonsEnabled(true)
                startWaveformAnimation()
            }
            VoiceSessionManager.State.ENDED -> {
                markInitialGreetingReceivedIfNeeded()
                stopWaveformAnimation()
                setControlButtonsEnabled(false)
            }
        }
    }

    private fun setControlButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        muteButton.isEnabled = enabled
        muteButton.alpha = alpha
        endButton.isEnabled = enabled
        endButton.alpha = alpha
        switchToChatMenuItem?.isEnabled = enabled
        switchToChatMenuItem?.icon?.alpha = if (enabled) 255 else 128
    }

    private fun showErrorState(message: String) {
        stopWaveformAnimation()
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
            muteButton.setImageResource(R.drawable.sierra_ic_mic_off_24)
        } else {
            voiceSession?.resumeListening()
            muteButton.setImageResource(R.drawable.sierra_ic_mic_24)
        }
    }

    private fun switchToChatTapped() {
        shutdownVoiceSessionIfNeeded(AgentVoiceCloseReason.CONTINUE_IN_CHAT)
        deliverSwitchToChatIfNeeded()
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
        Log.d(VOICE_TAG, "Voice credentials received for conversationId=$conversationID")
        voiceCallbacks?.onSessionInfoReceived(conversationID, encryptionKey)
    }

    override fun onReceiveResumeToken(token: String) {
        voiceCallbacks?.onResumeTokenReceived(token)
    }

    override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) {
        if (attachments.isNotEmpty()) {
            Handler(Looper.getMainLooper()).post {
                markInitialGreetingReceivedIfNeeded()
            }
        }

        if (attachments.isEmpty()) {
            return
        }

        val signature = canonicalizeForSignature(attachments)
        if (signature == lastRenderableAttachmentsSignature) {
            return
        }
        lastRenderableAttachmentsSignature = signature

        val agentAttachments = attachments.toAgentAttachments()

        Handler(Looper.getMainLooper()).post {
            if (agentAttachments.isNotEmpty()) {
                voiceCallbacks?.onAgentAttachment(agentAttachments)
            }

            if (rendererFailed) {
                return@post
            }
            ensureRendererLoaded()
            if (rendererFailed) {
                return@post
            }
            if (!hasShownFirstAttachment) {
                hasShownFirstAttachment = true
                placeholderContainer.visibility = View.GONE
                rendererView?.visibility = View.VISIBLE
            }
            rendererView?.pushAttachments(attachments)
        }
    }

    override fun onChangeState(state: VoiceSessionManager.State) {
        Handler(Looper.getMainLooper()).post {
            updateUIForState(state)
        }
    }

    override fun onError(error: Throwable) {
        Log.e(VOICE_TAG, "Voice session error", error)
        Handler(Looper.getMainLooper()).post {
            if (isExternalAudioInterruptionError(error)) {
                endConversationForExit()
                return@post
            }
            showErrorState(userFacingErrorMessage())
            voiceCallbacks?.onVoiceError(error)
        }
    }

    override fun onEnd() {
        Handler(Looper.getMainLooper()).post {
            updateUIForState(VoiceSessionManager.State.ENDED)
            shutdownVoiceSessionIfNeeded()
            deliverVoiceEndedIfNeeded()
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
        if (controller?.conversationEventListener?.onLinkClick(url) == true) {
            Log.i(VOICE_TAG, "External URL (${url.logSafeDescription()}) handled by host app")
            return
        }

        Log.i(VOICE_TAG, "External URL (${url.logSafeDescription()}) loaded, will open in the browser")
        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            requireContext().startActivity(intent)
        } catch (e: Throwable) {
            Log.w(VOICE_TAG, "Failed to start activity for URL (${url.logSafeDescription()})", e)
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

private val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()
