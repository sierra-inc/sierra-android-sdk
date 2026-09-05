// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentVoiceChatCoordinatorTest {
    @Test
    fun autoShowChatOnEndDoesNotRequireCanSwitchToChat() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
                canSwitchToChat = false,
                autoShowChatOnEnd = true,
            ),
        )
        val delegate = CapturingDelegate()
        coordinator.delegate = delegate

        coordinator.onVoiceEnded()

        assertEquals(0, delegate.voiceEndCount)
        assertEquals(1, delegate.showChatCount)
    }

    @Test
    fun handoffOverridesConversationListDefaultOnce() {
        val chatOptions = AgentChatControllerOptions(
            name = "Chat",
            userIdentityToken = "user-identity-token",
            enableConversationList = true,
            showConversationListByDefault = true,
        )
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = chatOptions,
            ),
        )

        coordinator.onVoiceEnded()
        val handoffUrl = loadedUrl(coordinator.makeChatController())
        val ordinaryUrl = loadedUrl(coordinator.makeChatController())

        assertFalse(handoffUrl.getBooleanQueryParameter("showConversationListByDefault", false))
        assertTrue(ordinaryUrl.getBooleanQueryParameter("showConversationListByDefault", false))
    }

    @Test
    fun canHideSwitchButtonWhileAutoShowingChatOnEnd() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
                canSwitchToChat = false,
                autoShowChatOnEnd = true,
            ),
        )

        val voiceController = coordinator.makeVoiceController()

        assertFalse(voiceController.options.canSwitchToChat)
        assertTrue(voiceController.options.autoShowChatOnEnd)
    }

    @Test
    fun sharesChatUserIdentityTokenWithVoice() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(
                    name = "Chat",
                    userIdentityToken = "user-identity-token",
                ),
            ),
        )

        assertEquals(
            "user-identity-token",
            coordinator.makeVoiceController().options.userIdentityToken,
        )
    }

    @Test
    fun emptyVoiceUserIdentityTokenFallsBackToChatToken() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(
                    name = "Voice",
                    userIdentityToken = "",
                ),
                chatOptions = AgentChatControllerOptions(
                    name = "Chat",
                    userIdentityToken = "user-identity-token",
                ),
            ),
        )

        assertEquals(
            "user-identity-token",
            coordinator.makeVoiceController().options.userIdentityToken,
        )
    }

    @Test
    fun noConfiguredVoiceConversationIDRestoresPersistedConversation() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("encryption-123", coordinator.encryptionKey)
        assertEquals("voice-123", coordinator.voiceConversationID)
        assertEquals("resume-123", coordinator.voiceResumeToken)
    }

    @Test
    fun voiceLaunchWithoutReconnectStartsFreshKeepingPersistedChatState() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        val voiceController = coordinator.makeVoiceController()

        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
        assertTrue(voiceController.options.continueInChatOnDismiss)
        assertNotNull(coordinator.voiceConversationID)
        assertNotEquals("voice-123", coordinator.voiceConversationID)
        assertNull(coordinator.voiceResumeToken)
        // In-memory chat credentials are cleared so an early dismissal can't seed the prior
        // conversation against the new voice ID; persisted storage keeps it resumable in chat.
        assertNull(coordinator.conversationID)
        assertNull(coordinator.encryptionKey)
        assertNotNull(agent.getStorage().getItem("embed-chat-test-token"))
    }

    @Test
    fun prepareVoiceReconnectResumesOnce() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.prepareVoiceReconnect()
        val reconnectController = coordinator.makeVoiceController()

        assertTrue(reconnectController.options.resumeConversation)
        assertEquals("resume-123", reconnectController.options.resumeToken)
        assertEquals(AgentVoiceResumeReason.CONTINUE_IN_VOICE, reconnectController.options.resumeReason)
        assertEquals("voice-123", reconnectController.options.voiceConversationID)

        // The latch is one-shot: the next launch starts fresh.
        val freshController = coordinator.makeVoiceController()

        assertFalse(freshController.options.resumeConversation)
        assertNull(freshController.options.resumeToken)
        assertNotEquals("voice-123", freshController.options.voiceConversationID)
    }

    @Test
    fun differentConfiguredVoiceConversationIDStartsFresh() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(
                    name = "Voice",
                    voiceConversationID = "voice-456",
                ),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("voice-123", coordinator.voiceConversationID)
        assertNotNull(agent.getStorage().getItem("embed-chat-test-token"))

        val voiceController = coordinator.makeVoiceController()

        assertNull(coordinator.conversationID)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
        assertEquals("voice-456", voiceController.options.voiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
    }

    @Test
    fun configuredVoiceConversationIDKeepsChatOnlyStateUntilVoiceStarts() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = null)
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(
                    name = "Voice",
                    voiceConversationID = "voice-456",
                ),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.makeChatController()

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("encryption-123", coordinator.encryptionKey)
        assertNotNull(agent.getStorage().getItem("embed-chat-test-token"))

        val voiceController = coordinator.makeVoiceController()

        assertNull(coordinator.conversationID)
        assertNull(coordinator.encryptionKey)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
        assertEquals("voice-456", voiceController.options.voiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
    }

    @Test
    fun changedVoiceConversationIDStartsFreshWhenVoiceControllerIsCreated() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val voiceOptions = AgentVoiceControllerOptions(
            name = "Voice",
            voiceConversationID = "voice-123",
        )
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = voiceOptions,
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        voiceOptions.voiceConversationID = "voice-456"

        val voiceController = coordinator.makeVoiceController()

        assertNull(coordinator.conversationID)
        assertEquals("voice-456", coordinator.voiceConversationID)
        assertEquals("voice-456", voiceController.options.voiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
    }

    @Test
    fun repeatedVoiceControllerCreationStartsFreshConversations() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.makeVoiceController()
        val firstVoiceConversationID = coordinator.voiceConversationID
        coordinator.makeVoiceController()

        assertNotNull(firstVoiceConversationID)
        assertNotNull(coordinator.voiceConversationID)
        assertNotEquals(firstVoiceConversationID, coordinator.voiceConversationID)
        assertNull(coordinator.voiceResumeToken)
    }

    @Test
    fun dismissalSeedsChatContinuationState() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-1", "enc-1")
        coordinator.onResumeTokenReceived("resume-1")

        coordinator.onVoiceDismissed()

        val json = agent.getStorage().getItem("embed-chat-test-token")
        assertNotNull(json)
        val state = JSONObject(json!!)
        assertEquals("chat-1", state.getString("conversationID"))
        assertEquals("enc-1", state.getString("encryptionKey"))
        assertTrue(state.getBoolean("continueInChatOnResume"))
        assertFalse(state.has("agentHandoffOnResume"))
        assertEquals("resume-1", state.getString("voiceResumeToken"))
        assertEquals("chat-1", coordinator.conversationID)
        assertEquals("enc-1", coordinator.encryptionKey)
    }

    @Test
    fun dismissalOverridesConversationListDefaultOnce() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val chatOptions = AgentChatControllerOptions(
            name = "Chat",
            userIdentityToken = "user-identity-token",
            enableConversationList = true,
            showConversationListByDefault = true,
        )
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = chatOptions,
            ),
        )
        coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-1", "enc-1")
        coordinator.onVoiceDismissed()

        val continuationUrl = loadedUrl(coordinator.makeChatController())
        val ordinaryUrl = loadedUrl(coordinator.makeChatController())

        assertFalse(continuationUrl.getBooleanQueryParameter("showConversationListByDefault", false))
        assertTrue(ordinaryUrl.getBooleanQueryParameter("showConversationListByDefault", false))
    }

    @Test
    fun restoredDismissalContinuationSuppressesConversationList() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val chatOptions = AgentChatControllerOptions(
            name = "Chat",
            userIdentityToken = "user-identity-token",
            enableConversationList = true,
            showConversationListByDefault = true,
        )
        val firstCoordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = chatOptions,
            ),
        )
        firstCoordinator.makeVoiceController()
        firstCoordinator.onSessionInfoReceived("chat-1", "enc-1")
        firstCoordinator.onVoiceDismissed()

        // Simulates an app restart before chat is opened.
        val recreatedCoordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = chatOptions,
            ),
        )
        val continuationUrl = loadedUrl(recreatedCoordinator.makeChatController())

        assertFalse(continuationUrl.getBooleanQueryParameter("showConversationListByDefault", false))
    }

    @Test
    fun dismissalAfterVoiceErrorResets() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-1", "enc-1")
        coordinator.onResumeTokenReceived("resume-1")
        coordinator.onVoiceError(IllegalStateException("connection failed"))

        coordinator.onVoiceDismissed()

        assertNull(coordinator.conversationID)
        assertNull(coordinator.encryptionKey)
        assertNull(coordinator.voiceConversationID)
        assertNull(coordinator.voiceResumeToken)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
    }

    @Test
    fun dismissalWithoutCredentialsLeavesNoState() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        coordinator.makeVoiceController()

        coordinator.onVoiceDismissed()

        assertNull(coordinator.conversationID)
        assertNull(coordinator.voiceConversationID)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
    }

    @Test
    fun dismissalBeforeSessionInfoPreservesPriorConversation() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.makeVoiceController()
        coordinator.onVoiceDismissed()

        // The abandoned launch leaves the persisted conversation untouched and re-syncs memory to
        // it, so the prior conversation keeps working -- including explicit voice reconnect.
        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("encryption-123", coordinator.encryptionKey)
        assertEquals("voice-123", coordinator.voiceConversationID)
        assertEquals("resume-123", coordinator.voiceResumeToken)
        val state = JSONObject(requireNotNull(agent.getStorage().getItem("embed-chat-test-token")))
        assertEquals("voice-123", state.getString("voiceConversationID"))
        assertEquals("resume-123", state.getString("voiceResumeToken"))

        coordinator.prepareVoiceReconnect()
        val reconnectController = coordinator.makeVoiceController()

        assertTrue(reconnectController.options.resumeConversation)
        assertEquals("resume-123", reconnectController.options.resumeToken)
        assertEquals("voice-123", reconnectController.options.voiceConversationID)
    }

    @Test
    fun errorBeforeSessionInfoPreservesPriorConversation() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.makeVoiceController()
        coordinator.onVoiceError(IllegalStateException("connection failed"))
        coordinator.onVoiceDismissed()

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("voice-123", coordinator.voiceConversationID)
        assertEquals("resume-123", coordinator.voiceResumeToken)
        assertNotNull(agent.getStorage().getItem("embed-chat-test-token"))
    }

    @Test
    fun voiceAfterDismissalStartsFreshConversation() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-1", "enc-1")
        coordinator.onResumeTokenReceived("resume-1")
        coordinator.onVoiceDismissed()
        val dismissedVoiceConversationID = coordinator.voiceConversationID

        val voiceController = coordinator.makeVoiceController()

        assertNotNull(dismissedVoiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
        assertNotEquals(dismissedVoiceConversationID, voiceController.options.voiceConversationID)
        // The dismissed conversation stays resumable in chat via persisted storage.
        assertNull(coordinator.conversationID)
        val state = JSONObject(requireNotNull(agent.getStorage().getItem("embed-chat-test-token")))
        assertEquals("chat-1", state.getString("conversationID"))
    }

    @Test
    fun voiceLaunchDropsStaleHandoffLatch() {
        val agent = Agent(AgentConfig(token = "test-token"))
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )
        val firstVoiceController = coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-1", "enc-1")
        // The agent requests a handoff but the host never presents chat.
        firstVoiceController.options.onSwitchToChat?.invoke(true)

        coordinator.makeVoiceController()
        coordinator.onSessionInfoReceived("chat-2", "enc-2")
        coordinator.makeChatController()

        // The stale handoff must not seed resume flags for the new conversation.
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
    }

    private fun persistConversationState(
        agent: Agent,
        voiceConversationID: String?,
        voiceResumeToken: String? = "resume-123",
    ) {
        val state = JSONObject()
            .put("conversationID", "chat-123")
            .put("encryptionKey", "encryption-123")
        voiceConversationID?.let { id ->
            state.put("voiceConversationID", id)
            voiceResumeToken?.let { state.put("voiceResumeToken", it) }
        }
        agent.getStorage().setItem("embed-chat-test-token", state.toString())
    }

    private fun loadedUrl(controller: AgentChatController): Uri {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = controller.createFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        val webView = (fragment.requireView() as ViewGroup).getChildAt(0) as WebView

        return Uri.parse(shadowOf(webView).lastLoadedUrl)
    }

    private class CapturingDelegate : AgentVoiceChatCoordinator.Delegate {
        var showChatCount = 0
        var voiceEndCount = 0

        override fun coordinatorDidRequestShowingChat(coord: AgentVoiceChatCoordinator) {
            showChatCount += 1
        }

        override fun coordinatorVoiceDidEnd(coord: AgentVoiceChatCoordinator) {
            voiceEndCount += 1
        }
    }
}
