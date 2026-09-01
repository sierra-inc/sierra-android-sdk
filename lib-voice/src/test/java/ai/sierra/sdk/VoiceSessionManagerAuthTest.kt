// Copyright Sierra

package ai.sierra.sdk

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class VoiceSessionManagerAuthTest {
    @Test
    fun oauthAccessTokenUsesAuthorizationHeadersAndTakesPrecedence() {
        val requestBuilder = Request.Builder().url("https://example.com")

        applySvpAuthentication(
            requestBuilder = requestBuilder,
            config = AgentConfig(
                token = "agent-token",
                headlessAPIToken = "headless-token",
                oauthAccessToken = "oauth-token",
            ),
        )

        val request = requestBuilder.build()
        assertEquals("Bearer oauth-token", request.header("Authorization"))
        assertEquals("2", request.header("X-Sierra-Token-Version"))
        assertNull(request.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun headlessTokenRemainsAvailableAsFallback() {
        val requestBuilder = Request.Builder().url("https://example.com")

        applySvpAuthentication(
            requestBuilder = requestBuilder,
            config = AgentConfig(
                token = "agent-token",
                headlessAPIToken = "headless-token",
            ),
        )

        val request = requestBuilder.build()
        assertEquals("Bearer headless-token", request.header("Authorization"))
        assertNull(request.header("X-Sierra-Token-Version"))
        assertNull(request.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun openMessageIncludesNonEmptyUserIdentityToken() {
        val authenticatedSession = VoiceSessionManager(
            config = AgentConfig(token = "agent-token"),
            userIdentityToken = "user-identity-token",
            delegate = NoopVoiceSessionDelegate,
        )
        val anonymousSession = VoiceSessionManager(
            config = AgentConfig(token = "agent-token"),
            userIdentityToken = "",
            delegate = NoopVoiceSessionDelegate,
        )

        assertEquals(
            "user-identity-token",
            authenticatedSession.buildOpenSubMessage().getString("userIdentityToken"),
        )
        assertEquals(
            "2026-08-27",
            authenticatedSession.buildOpenSubMessage().getString("compatibilityDate"),
        )
        assertFalse(anonymousSession.buildOpenSubMessage().has("userIdentityToken"))
    }

    private object NoopVoiceSessionDelegate : VoiceSessionDelegate {
        override fun onReceiveCredentials(conversationID: String, encryptionKey: String?) = Unit
        override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) = Unit
        override fun onChangeState(state: VoiceSessionManager.State) = Unit
        override fun onError(error: Throwable) = Unit
        override fun onEnd() = Unit
    }
}
