// Copyright Sierra

package ai.sierra.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(SierraInternalApi::class)
class AgentAPIHostTest {
    @Test
    fun regionalHostsUseRegionalURLs() {
        assertEquals("https://jp.api.sierra.chat", AgentAPIHost.JP.apiBaseURL)
        assertEquals("https://jp.sierra.chat", AgentAPIHost.JP.embedBaseURL)
        assertEquals("https://au.api.sierra.chat", AgentAPIHost.AU.apiBaseURL)
        assertEquals("https://au.sierra.chat", AgentAPIHost.AU.embedBaseURL)
    }
}
