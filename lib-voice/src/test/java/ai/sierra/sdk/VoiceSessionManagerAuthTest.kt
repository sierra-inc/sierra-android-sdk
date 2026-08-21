// Copyright Sierra

package ai.sierra.sdk

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
