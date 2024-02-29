// Copyright Sierra

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.json.JSONException
import org.json.JSONObject

/** Options for configuring an agent chat controller. */
@Parcelize
data class AgentChatControllerOptions(
    /** Name for this virtual agent, displayed as the navigation item title. */
    val name: String,
    /** Message shown from the agent when starting the conversation. */
    var greetingMessage: String = "How can I help you today?",
    /** Secondary text to display above the agent message at the start of a conversation. */
    var disclosure: String? = null,
    /** Message shown when an error is encountered during the conversation. */
    var errorMessage: String = "Oops, an error was encountered! Please try again.",
    /** Placeholder value displayed in the chat input when it is empty. */
    var inputPlaceholder: String = "Message…",
    /**
     * Hide the title bar in the fragment that the controller creates. The containing view is then
     * responsible for showing a title/app bar with the agent name.
     */
    val hideTitleBar: Boolean = false,
    /** Customize the colors and other appearance of the chat UI. */
    val chatStyle: ChatStyle = ChatStyle(),
    /** Customization of the Conversation that the controller will create. */
    var conversationOptions: ConversationOptions? = null
) : Parcelable {
    @IgnoredOnParcel
    var conversationEventListener: ConversationEventListener? = null
}

class AgentChatController(
    private val agent: Agent,
    private val options: AgentChatControllerOptions
) {
    fun createFragment(): Fragment {
        return AgentChatFragment().apply {
            arguments = Bundle().apply {
                putParcelable(
                    "args",
                    AgentChatFragmentArgs(agentConfig = agent.config, options = options)
                )
            }
            listener = MainThreadConversationEventListener(options.conversationEventListener)
        }
    }
}

@Parcelize
private data class AgentChatFragmentArgs(
    val agentConfig: AgentConfig,
    val options: AgentChatControllerOptions
) : Parcelable

class AgentChatFragment : Fragment() {
    private lateinit var webView: WebView
    internal var listener: ConversationEventListener? = null
    /**
     * Flag used to keep track that of whether the web view successfully loaded or not. We only
     * restore state (and avoid reloading the URL) if the last load was successful.
     * */
    internal var pageLoaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // We stash the value of listener in a view model so that when we're recreated we can still
        // get to it and invoke it.
        val viewModel = ViewModelProvider(this)[AgentChatViewModel::class.java]
        if (listener != null) {
            viewModel.listener = listener
        } else {
            listener = viewModel.listener
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args == null) {
            Log.w(TAG, "Could not find AgentChatFragment args, will not create web view")
            return View(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        webView = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val agentConfig = args.agentConfig
        val chatWebViewClient = ChatWebViewClient(this, agentConfig, args.options, listener)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = generateUserAgent(requireContext())
            webViewClient = chatWebViewClient
            addJavascriptInterface(ChatWebViewInterface(listener), "AndroidSDK")
        }
        if (agentConfig.apiHost == AgentAPIHost.LOCAL) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        return webView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args == null) {
            Log.w(TAG, "Could not find AgentChatFragment args, will not initialize web view")
            return
        }

        if (savedInstanceState != null && savedInstanceState.getBoolean("pageLoaded")) {
            val savedInstanceArgs = savedInstanceState.getParcelable<AgentChatFragmentArgs>("args")
            if (savedInstanceArgs == args) {
                webView.restoreState(savedInstanceState)
                return
            }
        }

        val agentConfig = args.agentConfig
        val options = args.options
        // Turn config and options into query parameters that android.tsx expects.
        val urlBuilder = Uri.parse(agentConfig.url).buildUpon()

        // Should match the Brand type from bots/useChat.tsx
        val brandJSON = JSONObject(
            mapOf(
                "botName" to options.name,
                "errorMessage" to options.errorMessage,
                "greetingMessage" to options.greetingMessage,
                "disclosure" to options.disclosure,
                "inputPlaceholder" to options.inputPlaceholder,
                "chatStyle" to JSONObject(options.chatStyle.toJSON()).toString(),
            )
        ).toString()

        urlBuilder.appendQueryParameter("brand", brandJSON)
        if (options.hideTitleBar) {
            urlBuilder.appendQueryParameter("hideTitleBar", "true")
        }
        val conversationOptions = options.conversationOptions ?: ConversationOptions()
        val locale = conversationOptions.locale ?: resources.configuration.locales[0]
        urlBuilder.appendQueryParameter("locale", locale.toLanguageTag())
        for ((name, value) in conversationOptions.variables) {
            urlBuilder.appendQueryParameter("variable", "$name:$value")
        }
        for ((name, value) in conversationOptions.secrets) {
            urlBuilder.appendQueryParameter("secret", "$name:$value")
        }

        val url = urlBuilder.build().toString()
        // Ensure that there's no chat state from previous runs still present.
        CookieManager.getInstance().removeSessionCookies {
            webView.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putBoolean("pageLoaded", pageLoaded)
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args != null) {
            outState.putParcelable("args", args)
        }
    }
}

private fun generateUserAgent(context: Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion = packageInfo.versionName ?: "0"
    val appName = context.packageName
    val androidVersion = Build.VERSION.RELEASE
    val model = Build.MODEL

    return "Sierra-Android-SDK ($appName/$appVersion $model/$androidVersion)"
}

internal class AgentChatViewModel : ViewModel() {
    internal var listener: ConversationEventListener? = null
}

private class ChatWebViewClient(
    private val fragment: AgentChatFragment,
    private val agentConfig: AgentConfig,
    private val options: AgentChatControllerOptions,
    private val listener: ConversationEventListener?,
) : WebViewClient() {
    private var hadError: Boolean = false

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url.toString().startsWith(agentConfig.url) && !hadError) {
            fragment.pageLoaded = true
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // Ignore SSL errors for the local development certificate.
        if (agentConfig.apiHost == AgentAPIHost.LOCAL && error?.url?.startsWith(agentConfig.url) == true) {
            Log.w(TAG, "Ignoring SSL error for local URL ${error.url}")
            handler?.proceed()
        } else {
            handler?.cancel()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.url.toString().startsWith(agentConfig.url)) {
            Log.e(
                TAG,
                "Received error trying to load the main URL: code=${error.errorCode} description=${error.description}"
            )
            view.loadUrl("about:blank")
            fragment.pageLoaded = false
            hadError = true
            listener?.onConversationInitializationError()
        }
    }
}

private class ChatWebViewInterface(private val listener: ConversationEventListener?) {
    @JavascriptInterface
    fun onTransfer(dataJSONStr: String) {
        val dataJSON = try {
            JSONObject(dataJSONStr)
        } catch (e: JSONException) {
            Log.e(TAG, "Cannot parse transfer JSON data", e)
            return
        }
        val isSynchronous = dataJSON.optBoolean("isSynchronous")
        val dataArrayJSON = dataJSON.optJSONArray("data")
        val dataMap = mutableMapOf<String, String>()
        if (dataArrayJSON != null) {
            for (i in 0 until dataArrayJSON.length()) {
                val item = dataArrayJSON.getJSONObject(i)
                dataMap[item.getString("key")] = item.getString("value")
            }
        }

        val transfer = ConversationTransfer(isSynchronous, dataMap)
        listener?.onConversationTransfer(transfer)
    }
}

private const val TAG = "AgentChatController"
