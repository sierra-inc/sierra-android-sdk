// Copyright Sierra

package ai.sierra.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class VoiceSessionManagerReconnectTest {
    @Test
    fun reconnectResumesOnlyWhenTheServerHasAConversation() {
        val newSession = VoiceSessionManager(
            config = AgentConfig(token = "agent-token"),
            delegate = NoopVoiceSessionDelegate,
        )
        newSession.handleMessage(opened(resumeToken = "server-token"))

        val freshOpen = newSession.buildOpenSubMessage(isReconnect = true)
        assertFalse(freshOpen.has("resumeToken"))

        newSession.handleMessage(sessionInfo())

        val resumedOpen = newSession.buildOpenSubMessage(isReconnect = true)
        assertEquals("server-token", resumedOpen.getString("resumeToken"))
        assertEquals(freshOpen.getString("conversationId"), resumedOpen.getString("conversationId"))

        val resumeWithoutTokenSession = VoiceSessionManager(
            config = AgentConfig(token = "agent-token"),
            resumeConversation = true,
            delegate = NoopVoiceSessionDelegate,
        )
        resumeWithoutTokenSession.handleMessage(opened(resumeToken = "server-token"))
        assertFalse(resumeWithoutTokenSession.buildOpenSubMessage(isReconnect = true).has("resumeToken"))

        val handoffSession = VoiceSessionManager(
            config = AgentConfig(token = "agent-token"),
            resumeConversation = true,
            resumeReason = AgentVoiceResumeReason.CONTINUE_IN_VOICE,
            resumeToken = "host-token",
            delegate = NoopVoiceSessionDelegate,
        )

        val handoffBeforeSessionInfo = handoffSession.buildOpenSubMessage(isReconnect = true)
        assertEquals("host-token", handoffBeforeSessionInfo.getString("resumeToken"))
        assertEquals("continue_in_voice", handoffBeforeSessionInfo.getString("resumeReason"))

        handoffSession.handleMessage(sessionInfo())

        val handoffAfterSessionInfo = handoffSession.buildOpenSubMessage(isReconnect = true)
        assertEquals("host-token", handoffAfterSessionInfo.getString("resumeToken"))
        assertFalse(handoffAfterSessionInfo.has("resumeReason"))
    }

    private fun opened(resumeToken: String): String =
        JSONObject()
            .put("type", "opened")
            .put("subMsg", JSONObject().put("resumeToken", resumeToken))
            .toString()

    private fun sessionInfo(): String =
        JSONObject()
            .put("type", "session_info")
            .put(
                "subMsg",
                JSONObject()
                    .put("conversationId", "conversation-id")
                    .put("encryptionKey", "encryption-key"),
            )
            .toString()

    private object NoopVoiceSessionDelegate : VoiceSessionDelegate {
        override fun onReceiveCredentials(conversationID: String, encryptionKey: String?) = Unit
        override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) = Unit
        override fun onChangeState(state: VoiceSessionManager.State) = Unit
        override fun onError(error: Throwable) = Unit
        override fun onEnd() = Unit
    }
}
