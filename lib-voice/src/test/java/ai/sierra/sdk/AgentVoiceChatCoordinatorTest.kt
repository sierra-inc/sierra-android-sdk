// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
