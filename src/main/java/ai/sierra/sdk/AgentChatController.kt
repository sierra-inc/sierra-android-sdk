// Copyright Sierra

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment

/**
 * Options for configuring an agent chat controller.
 *
 * @property name Name for this virtual agent, displayed as the navigation item title.
 * @property greetingMessage Message shown from the agent when starting the conversation.
 * @property disclosure Secondary text to display above the agent message at the start of a conversation.
 * @property errorMessage Message shown when an error is encountered during the conversation.
 * @property inputPlaceholder Placeholder value displayed in the chat input when it is empty.
 * @property conversationOptions Customization of the Conversation that the controller will create.
 */
data class AgentChatControllerOptions(
    val name: String,
    var greetingMessage: String = "How can I help you today?",
    var disclosure: String? = null,
    var errorMessage: String = "Oops, an error was encountered! Please try again.",
    var inputPlaceholder: String = "Message…",
    var conversationOptions: ConversationOptions? = null
)
class AgentChatController(private val agent: Agent, private val options: AgentChatControllerOptions) {

    fun createFragment(): Fragment {
        return AgentChatFragment(agent.config)
    }
}

class AgentChatFragment(private val config: AgentConfig) : Fragment() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val webView = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView.apply {
            settings.javaScriptEnabled = true
            webViewClient = AgentChatFragmentWebViewClient(config)
            loadUrl(config.url)
        }
        if (config.apiHost == AgentAPIHost.LOCAL) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        return webView
    }
}

class AgentChatFragmentWebViewClient(private val config: AgentConfig) : WebViewClient() {
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // Ignore SSL errors for the local development certificate.
        if (config.apiHost == AgentAPIHost.LOCAL && error?.url == config.url) {
            println("Ignoring SSL error for local URL ${error?.url}")
            handler?.proceed()
        } else {
            handler?.cancel()
        }
    }
}