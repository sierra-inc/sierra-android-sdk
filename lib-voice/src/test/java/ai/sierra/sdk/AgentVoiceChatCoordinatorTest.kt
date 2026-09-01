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
    fun matchingConfiguredVoiceConversationIDResumesPersistedConversation() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(agent, voiceConversationID = "voice-123")
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(
                    name = "Voice",
                    voiceConversationID = "voice-123",
                ),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("voice-123", coordinator.voiceConversationID)
        assertNotNull(agent.getStorage().getItem("embed-chat-test-token"))

        val voiceController = coordinator.makeVoiceController()

        assertEquals("chat-123", coordinator.conversationID)
        assertEquals("voice-123", voiceController.options.voiceConversationID)
        assertTrue(voiceController.options.resumeConversation)
        assertEquals("resume-123", voiceController.options.resumeToken)
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
    fun matchingConfiguredVoiceConversationIDWithoutResumeTokenGeneratesFreshID() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(
            agent,
            voiceConversationID = "voice-123",
            voiceResumeToken = null,
        )
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(
                    name = "Voice",
                    voiceConversationID = "voice-123",
                ),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        val voiceController = coordinator.makeVoiceController()

        assertNull(coordinator.conversationID)
        assertNull(coordinator.encryptionKey)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
        assertNotNull(coordinator.voiceConversationID)
        assertEquals(coordinator.voiceConversationID, voiceController.options.voiceConversationID)
        assertNotEquals("voice-123", voiceController.options.voiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
    }

    @Test
    fun sdkManagedVoiceConversationIDWithoutResumeTokenGeneratesFreshID() {
        val agent = Agent(AgentConfig(token = "test-token"))
        persistConversationState(
            agent,
            voiceConversationID = "voice-123",
            voiceResumeToken = null,
        )
        val coordinator = AgentVoiceChatCoordinator(
            agent = agent,
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        val voiceController = coordinator.makeVoiceController()

        assertNull(coordinator.conversationID)
        assertNull(coordinator.encryptionKey)
        assertNull(agent.getStorage().getItem("embed-chat-test-token"))
        assertNotNull(voiceController.options.voiceConversationID)
        assertNotEquals("voice-123", voiceController.options.voiceConversationID)
        assertFalse(voiceController.options.resumeConversation)
        assertNull(voiceController.options.resumeToken)
    }

    @Test
    fun repeatedVoiceControllerCreationKeepsIDWhileResumeTokenIsPending() {
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = AgentVoiceControllerOptions(name = "Voice"),
                chatOptions = AgentChatControllerOptions(name = "Chat"),
            ),
        )

        coordinator.makeVoiceController()
        val pendingVoiceConversationID = coordinator.voiceConversationID
        coordinator.makeVoiceController()

        assertNotNull(pendingVoiceConversationID)
        assertEquals(pendingVoiceConversationID, coordinator.voiceConversationID)
        assertNull(coordinator.voiceResumeToken)
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
