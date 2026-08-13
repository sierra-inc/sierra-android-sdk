// Copyright Sierra

package ai.sierra.sdk.compose

import android.content.Context
import android.content.Intent
import android.webkit.WebChromeClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ai.sierra.sdk.AgentChatController
import ai.sierra.sdk.AgentChatView

/**
 * Hosts Sierra agent chat in a Compose UI without requiring a FragmentManager.
 *
 * Chat state survives configuration changes and activity recreation through the view's own
 * [AgentChatView.onSaveInstanceState], which `AndroidView` drives because the view carries a stable
 * id. That is why this composable saves no state by hand.
 *
 * State is discarded when this composable leaves the composition. To restore it after navigation,
 * tab, sheet, or conditional removal, keep it composed or wrap it in a stable
 * `rememberSaveableStateHolder().SaveableStateProvider`.
 *
 * Changing [controller] releases the current view and creates a replacement, so a chat view is
 * never re-pointed at a different controller. Recreating an equivalent controller after Activity
 * recreation preserves the host's saved-state slot and restores the existing chat.
 */
@Composable
fun SierraAgentChat(
    controller: AgentChatController,
    modifier: Modifier = Modifier,
) {
    val adapter = remember(controller) { AgentChatViewAdapter(controller) }
    ManagedAgentChatView(
        hostKey = Unit,
        instanceKey = controller,
        modifier = modifier,
        adapter = adapter,
    )
}

/** Binds the WebView-backed [AgentChatView] to the Compose host contract. */
private class AgentChatViewAdapter(
    private val controller: AgentChatController,
) : ChatViewAdapter<AgentChatView> {
    override fun createView(
        context: Context,
        launchFileChooser: (Intent) -> Unit,
    ): AgentChatView = controller.createView(
        context = context,
        fileChooserLauncher = launchFileChooser,
    )

    override fun dispose(view: AgentChatView) {
        view.dispose()
    }

    override fun onFileChooserResult(view: AgentChatView, resultCode: Int, data: Intent?) {
        view.onFileChooserResult(
            WebChromeClient.FileChooserParams.parseResult(resultCode, data),
        )
    }
}
