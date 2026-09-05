// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONException
import org.json.JSONObject

/**
 * Coordinates unified voice and chat controllers so a voice conversation can continue in chat.
 *
 * Keep behavior in sync with AgentVoiceChatCoordinator.swift in the iOS SDK.
 */
public class AgentVoiceChatCoordinator(
    private val agent: Agent,
    private val options: Options,
) : VoiceCallbacks {
    public data class Options(
        val voiceOptions: AgentVoiceControllerOptions,
        val chatOptions: AgentChatControllerOptions,
        var agentEventListener: AgentEventListener? = null,
        /**
         * When true, the voice view includes a navigation-bar button that lets the user switch
         * from voice to chat without ending the conversation. On tap, the SVP session is closed
         * with the `continue_in_chat` close reason and the chat view is presented with the
         * transcript preserved. Dismissing the voice view (e.g. back navigation) also closes the
         * session with `continue_in_chat`, keeping the conversation resumable in chat.
         */
        val canSwitchToChat: Boolean = true,
        /**
         * When true, the voice session's natural end -- whether the user taps the End button or the
         * agent ends the conversation server-side -- is treated like a switch-to-chat: the
         * coordinator fires [Delegate.coordinatorDidRequestShowingChat] instead of
         * [Delegate.coordinatorVoiceDidEnd], and the chat view opens with the voice transcript
         * seeded. Independent from [canSwitchToChat], which only controls whether the manual
         * navigation bar button is shown.
         */
        val autoShowChatOnEnd: Boolean = true,
    )

    public interface Delegate {
        /**
         * Called when the user taps the switch-to-chat button in voice. The host should present the
         * chat controller created via [makeChatController] so the transcript is preserved.
         */
        public fun coordinatorDidRequestShowingChat(coord: AgentVoiceChatCoordinator)

        /**
         * Called when the voice session ends naturally. The host should typically dismiss or pop
         * the voice fragment.
         */
        public fun coordinatorVoiceDidEnd(coord: AgentVoiceChatCoordinator) {}

        /** Called when the voice session encounters an error. */
        public fun onVoiceError(coord: AgentVoiceChatCoordinator, error: Throwable) {}

        /** Called when the voice session receives agent-produced attachments. */
        public fun onAgentAttachment(
            coord: AgentVoiceChatCoordinator,
            attachments: List<AgentAttachment>
        ) {}
    }

    public var delegate: Delegate? = null
    public var voiceConversationID: String? = null
        private set
    public var conversationID: String? = null
        private set
    public var encryptionKey: String? = null
        private set
    public var voiceResumeToken: String? = null
        private set

    public var agentEventListener: AgentEventListener? = options.agentEventListener
    // One-shot latch: armed by the voice switch action, a dismissal that seeded continuation
    // state, or unconsumed seeded state found at init. Consumed by the next makeChatController()
    // call, which seeds storage and suppresses the conversation list for that first presentation.
    private val pendingContinueInChat = AtomicBoolean(false)
    // True when the pending switch was agent-initiated (vs a manual "Continue in chat" tap); the
    // seeded chat state then drives the agent on resume instead of switching silently.
    private val pendingAgentHandoff = AtomicBoolean(false)
    // One-shot: armed by the reconnect-to-voice action ([prepareVoiceReconnect]), consumed by the
    // next makeVoiceController() call. All other voice launches start a new conversation.
    private val pendingReconnectVoice = AtomicBoolean(false)
    // Set when the voice session reports an error; the server then treats the call as disconnected
    // and terminal, so a later dismissal resets instead of seeding chat continuation state.
    private val voiceSessionErrored = AtomicBoolean(false)

    init {
        restorePersistedConversationState()
    }

    public fun makeVoiceController(): AgentVoiceController {
        val voiceOptions = options.voiceOptions.copy()
        // data class copy() only carries primary-constructor params; preserve and re-set the
        // @IgnoredOnParcel body properties below.
        voiceOptions.muteButtonProvider = options.voiceOptions.muteButtonProvider
        voiceOptions.unmuteButtonProvider = options.voiceOptions.unmuteButtonProvider
        voiceOptions.endCallButtonProvider = options.voiceOptions.endCallButtonProvider
        voiceOptions.compactMuteButtonProvider = options.voiceOptions.compactMuteButtonProvider
        voiceOptions.compactUnmuteButtonProvider = options.voiceOptions.compactUnmuteButtonProvider
        voiceOptions.compactEndCallButtonProvider = options.voiceOptions.compactEndCallButtonProvider
        voiceOptions.textComposerViewProvider = options.voiceOptions.textComposerViewProvider
        voiceOptions.voiceOkHttpClientCustomizer = options.voiceOptions.voiceOkHttpClientCustomizer
        if (voiceOptions.userIdentityToken.isNullOrEmpty()) {
            voiceOptions.userIdentityToken = options.chatOptions.userIdentityToken
        }
        val isReconnect = pendingReconnectVoice.getAndSet(false)
        voiceSessionErrored.set(false)
        // Launching voice supersedes any voice-to-chat handoff the host never presented; drop the
        // latch so a later chat open doesn't seed a stale resume flag.
        pendingContinueInChat.set(false)
        pendingAgentHandoff.set(false)
        val configuredVoiceConversationID = voiceOptions.voiceConversationID
        val configuredIDChanged =
            configuredVoiceConversationID != null &&
                configuredVoiceConversationID != voiceConversationID
        if (configuredIDChanged) {
            resetConversation()
        }
        val shouldResumeConversation =
            isReconnect && voiceConversationID != null && voiceResumeToken != null
        if (!shouldResumeConversation) {
            // Voice resume is an explicit, one-shot reconnect action; every other launch starts a
            // new voice conversation. Clear the in-memory chat credentials too: they describe the
            // previous conversation, and a dismissal before this session delivers its own
            // credentials must not seed them against this launch's voice ID. The previous
            // conversation stays resumable through persisted storage, which is left untouched
            // until this session seeds its replacement.
            voiceConversationID = configuredVoiceConversationID ?: UUID.randomUUID().toString()
            voiceResumeToken = null
            conversationID = null
            encryptionKey = null
        }

        voiceOptions.voiceConversationID = voiceConversationID
        voiceOptions.resumeConversation = shouldResumeConversation
        voiceOptions.resumeToken = voiceResumeToken
        if (shouldResumeConversation) {
            voiceOptions.resumeReason = AgentVoiceResumeReason.CONTINUE_IN_VOICE
        }
        voiceOptions.onSwitchToChat = { agentInitiated -> handleSwitchToChat(agentInitiated) }
        voiceOptions.canSwitchToChat = options.canSwitchToChat
        voiceOptions.autoShowChatOnEnd = options.autoShowChatOnEnd
        voiceOptions.continueInChatOnDismiss = true

        return AgentVoiceController(agent, voiceOptions).also { controller ->
            controller.conversationEventListener = options.chatOptions.conversationEventListener
            controller.voiceCallbacks = this
        }
    }

    public fun makeChatController(): AgentChatController {
        // The voice switch action sets this latch immediately before asking the host to present
        // chat. The first chat controller after that transition seeds storage, then clears it so
        // normal chat openings do not overwrite current state.
        val isVoiceToChatHandoff = pendingContinueInChat.compareAndSet(true, false)
        if (isVoiceToChatHandoff) {
            seedChatContinuationStateIfAvailable(pendingAgentHandoff.getAndSet(false))
        }

        val chatOptions = options.chatOptions.copy(
            showConversationListByDefault =
                options.chatOptions.showConversationListByDefault && !isVoiceToChatHandoff,
            userIdentityToken = options.chatOptions.userIdentityToken
                .takeUnless { it.isNullOrEmpty() } ?: options.voiceOptions.userIdentityToken,
        )
        // data class copy() only carries primary-constructor params; preserve and re-set the
        // @IgnoredOnParcel body properties below.
        chatOptions.conversationEventListener = options.chatOptions.conversationEventListener
            ?: agentEventListener?.let { ChatEventListenerAdapter(it) }
        chatOptions.onConversationEndedInternal = { resetConversation() }
        return AgentChatController(agent, chatOptions)
    }

    public fun resetConversation() {
        voiceConversationID = null
        conversationID = null
        encryptionKey = null
        voiceResumeToken = null
        pendingContinueInChat.set(false)
        pendingAgentHandoff.set(false)
        pendingReconnectVoice.set(false)
        voiceSessionErrored.set(false)
        agent.resetConversation()
    }

    /**
     * Arms the next [makeVoiceController] call to resume the current voice conversation instead of
     * starting a new one; the server then emits a `continue-in-voice` client event so the agent can
     * greet the user back to voice. Call this when presenting voice from a custom
     * reconnect-to-voice control. One-shot: consumed by the next [makeVoiceController] call.
     */
    public fun prepareVoiceReconnect() {
        pendingReconnectVoice.set(true)
    }

    override fun onVoiceEnded() {
        if (options.autoShowChatOnEnd) {
            handleSwitchToChat(agentInitiated = false)
            return
        }
        resetConversation()
        delegate?.coordinatorVoiceDidEnd(this)
    }

    override fun onVoiceDismissed() {
        // Dismissal (e.g. back navigation) closes the voice leg with `continue_in_chat`, so persist
        // the continuation state immediately -- the user may not open chat until after an app
        // restart.
        if (conversationID == null || encryptionKey == null) {
            // This session never delivered credentials (dismissed or errored before session info
            // arrived), so it created nothing continuable. Leave persisted storage untouched and
            // re-sync memory to it so the previous conversation, if any, stays resumable in chat
            // and voice.
            voiceConversationID = null
            conversationID = null
            encryptionKey = null
            voiceResumeToken = null
            restorePersistedConversationState()
            return
        }
        if (voiceSessionErrored.get()) {
            // The server treats an errored call as disconnected and terminal; nothing to resume.
            resetConversation()
            return
        }
        seedChatContinuationStateIfAvailable(agentInitiated = false)
        // Arm the one-shot latch so the next chat open lands on the continued transcript instead
        // of the conversation list.
        pendingContinueInChat.set(true)
        pendingAgentHandoff.set(false)
    }

    override fun onVoiceError(error: Throwable) {
        voiceSessionErrored.set(true)
        delegate?.onVoiceError(this, error)
    }

    override fun onAgentAttachment(attachments: List<AgentAttachment>) {
        delegate?.onAgentAttachment(this, attachments)
    }

    override fun onSecretExpiry(secretName: String, replyHandler: (SecretExpiryResult) -> Unit) {
        val listener = agentEventListener
        if (listener == null) {
            replyHandler(SecretExpiryResult.Success(null))
            return
        }
        listener.onSecretExpiry(secretName, replyHandler)
    }

    override fun onLinkClick(url: android.net.Uri): Boolean {
        return agentEventListener?.onLinkClick(url) ?: false
    }

    override fun onSessionInfoReceived(conversationID: String, encryptionKey: String?) {
        this.conversationID = conversationID
        this.encryptionKey = encryptionKey
    }

    override fun onResumeTokenReceived(token: String) {
        this.voiceResumeToken = token
    }

    private fun handleSwitchToChat(agentInitiated: Boolean) {
        // The next makeChatController() call consumes this latch to seed the web chat state for
        // this explicit voice-to-chat handoff. Plain chat opens leave the latch false.
        pendingContinueInChat.set(true)
        pendingAgentHandoff.set(agentInitiated)
        delegate?.coordinatorDidRequestShowingChat(this)
    }

    private fun seedChatContinuationStateIfAvailable(agentInitiated: Boolean) {
        val conversationID = conversationID ?: return
        val encryptionKey = encryptionKey ?: return
        val state = JSONObject()
            .put("conversationID", conversationID)
            .put("encryptionKey", encryptionKey)
        // An agent-initiated handoff drives the chat agent (the embed sends a continue-in-chat
        // client event on resume); a manual switch stays silent. The embed reads exactly one flag.
        if (agentInitiated) {
            state.put("agentHandoffOnResume", true)
        } else {
            state.put("continueInChatOnResume", true)
        }
        voiceConversationID?.let { id ->
            state.put("voiceConversationID", id)
        }
        voiceResumeToken?.let { token ->
            state.put("voiceResumeToken", token)
        }
        agent.getStorage().setItem(persistedConversationStorageKey(), state.toString())
    }

    private fun persistedConversationStorageKey(): String {
        // Keep this in sync with the web embed's persisted conversation key.
        return "embed-chat-${agent.config.token}"
    }

    private fun restorePersistedConversationState() {
        val state = loadPersistedConversationState() ?: return
        conversationID = state.optString("conversationID").takeIf { it.isNotEmpty() }
        encryptionKey = state.optString("encryptionKey").takeIf { it.isNotEmpty() }
        voiceConversationID = state.optString("voiceConversationID").takeIf { it.isNotEmpty() }
        voiceResumeToken = state.optString("voiceResumeToken").takeIf { it.isNotEmpty() }
        // Seeded resume flags mean the last voice session ended toward chat and no chat controller
        // has consumed them yet (the embed strips them once chat opens). Re-arm the one-shot latch
        // so the next chat open still lands on the continued transcript instead of the list, even
        // across an app restart.
        val agentHandoffOnResume = state.optBoolean("agentHandoffOnResume", false)
        pendingAgentHandoff.set(agentHandoffOnResume)
        pendingContinueInChat.set(
            agentHandoffOnResume || state.optBoolean("continueInChatOnResume", false)
        )
    }

    private fun loadPersistedConversationState(): JSONObject? {
        val jsonString = agent.getStorage().getItem(persistedConversationStorageKey()) ?: return null
        return try {
            JSONObject(jsonString)
        } catch (_: JSONException) {
            null
        }
    }
}

private class ChatEventListenerAdapter(
    private val listener: AgentEventListener
) : ConversationEventListener {
    override fun onSecretExpiry(secretName: String, replyHandler: (SecretExpiryResult) -> Unit) {
        listener.onSecretExpiry(secretName, replyHandler)
    }

    override fun onLinkClick(url: android.net.Uri): Boolean {
        return listener.onLinkClick(url)
    }
}
