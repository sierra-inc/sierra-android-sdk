// Copyright Sierra

package ai.sierra.sdk

import android.content.Context
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider

/**
 * Builds an uninitialized [AgentChatView] under Robolectric. The internal constructor takes an
 * [AgentConfig] directly, so no [Agent], network, or real token is involved, and construction alone
 * loads nothing: callers decide when initialization happens.
 */
internal fun createTestChatView(
    fileChooserLauncher: ((android.content.Intent) -> Unit)? = null,
    onDispose: ((AgentChatView) -> Unit)? = null,
): AgentChatView = AgentChatView(
    context = ApplicationProvider.getApplicationContext<Context>(),
    agentConfig = AgentConfig(token = "test-token"),
    options = AgentChatControllerOptions(name = "Test Agent"),
    conversationState = null,
    listener = null,
    storage = null,
    fileChooserLauncher = fileChooserLauncher,
    onConversationEndedInternal = null,
    onDispose = onDispose,
    viewId = R.id.sierra_agent_chat_view,
)

private fun AgentChatView.children() = (0 until childCount).map { getChildAt(it) }

/** The chat WebView is a child of the view, which keeps its own reference private. */
internal fun AgentChatView.chatWebView(): WebView =
    children().filterIsInstance<WebView>().single()

/**
 * The loading spinner starts visible and is hidden once the chat content is revealed, so its
 * visibility distinguishes a state restore from a fresh page load.
 */
internal fun AgentChatView.loadingSpinner(): ProgressBar =
    children().filterIsInstance<ProgressBar>().single()
