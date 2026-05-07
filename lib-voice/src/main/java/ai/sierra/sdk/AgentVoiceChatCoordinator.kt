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
        val canSwitchToChat: Boolean = true,
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

    // One-shot handoff latch: set by the voice switch action and consumed by the next
    // makeChatController() call so only the first chat presentation after handoff seeds storage.
    private val pendingContinueInChat = AtomicBoolean(false)

    init {
        restorePersistedConversationState()
    }

    public fun makeVoiceController(): AgentVoiceController {
        val voiceOptions = options.voiceOptions.copy()
        val shouldResumeConversation =
            voiceConversationID != null ||
                (voiceOptions.resumeConversation && voiceOptions.voiceConversationID != null)
        val nextVoiceConversationID =
            voiceConversationID ?: voiceOptions.voiceConversationID ?: UUID.randomUUID().toString()
        voiceConversationID = nextVoiceConversationID

        voiceOptions.voiceConversationID = nextVoiceConversationID
        voiceOptions.resumeConversation = shouldResumeConversation
        voiceOptions.resumeToken = voiceResumeToken
        if (shouldResumeConversation) {
            voiceOptions.resumeReason = AgentVoiceResumeReason.CONTINUE_IN_VOICE
        }
        if (options.canSwitchToChat) {
            voiceOptions.canSwitchToChat = true
            voiceOptions.onSwitchToChat = { handleSwitchToChat() }
        }

        return AgentVoiceController(agent, voiceOptions).also { controller ->
            controller.conversationEventListener = options.chatOptions.conversationEventListener
            controller.voiceCallbacks = this
        }
    }

    public fun makeChatController(): AgentChatController {
        // The voice switch action sets this latch immediately before asking the host to present
        // chat. The first chat controller after that transition seeds storage, then clears it so
        // normal chat openings do not overwrite current state.
        if (pendingContinueInChat.compareAndSet(true, false)) {
            seedChatContinuationStateIfAvailable()
        }

        val chatOptions = options.chatOptions.copy()
        chatOptions.conversationEventListener = options.chatOptions.conversationEventListener
        chatOptions.onConversationEndedInternal = { resetConversation() }
        return AgentChatController(agent, chatOptions)
    }

    public fun resetConversation() {
        voiceConversationID = null
        conversationID = null
        encryptionKey = null
        voiceResumeToken = null
        pendingContinueInChat.set(false)
        agent.resetConversation()
    }

    override fun onVoiceEnded() {
        resetConversation()
        delegate?.coordinatorVoiceDidEnd(this)
    }

    override fun onVoiceError(error: Throwable) {
        delegate?.onVoiceError(this, error)
    }

    override fun onAgentAttachment(attachments: List<AgentAttachment>) {
        delegate?.onAgentAttachment(this, attachments)
    }

    override fun onSessionInfoReceived(conversationID: String, encryptionKey: String?) {
        this.conversationID = conversationID
        this.encryptionKey = encryptionKey
    }

    override fun onResumeTokenReceived(token: String) {
        this.voiceResumeToken = token
    }

    private fun handleSwitchToChat() {
        // The next makeChatController() call consumes this latch to seed the web chat state for
        // this explicit voice-to-chat handoff. Plain chat opens leave the latch false.
        pendingContinueInChat.set(true)
        delegate?.coordinatorDidRequestShowingChat(this)
    }

    private fun seedChatContinuationStateIfAvailable() {
        val conversationID = conversationID ?: return
        val encryptionKey = encryptionKey ?: return
        val state = JSONObject()
            .put("conversationID", conversationID)
            .put("encryptionKey", encryptionKey)
            .put("continueInChatOnResume", true)
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
        if (voiceConversationID == null) {
            voiceConversationID = state.optString("voiceConversationID").takeIf { it.isNotEmpty() }
        }
        if (voiceResumeToken == null) {
            voiceResumeToken = state.optString("voiceResumeToken").takeIf { it.isNotEmpty() }
        }
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
