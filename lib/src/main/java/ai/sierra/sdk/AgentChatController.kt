// Copyright Sierra

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.print.PrintAttributes
import android.print.PrintManager


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
    /** Message shown in place of the chat input when the conversation has ended. */
    var conversationEndedMessage: String = "Chat Ended",

    /** Message shown when waiting for a human agent to join the conversation. */
    var agentTransferWaitingMessage: String = "Waiting for agent…",
    /** Message shown when a human agent has joined the conversation. */
    var agentJoinedMessage: String = "Agent connected",
    /** Message shown when a human agent has left the conversation. */
    var agentLeftMessage: String = "Agent disconnected",

   /**
     * Hide the title bar in the fragment that the controller creates. The containing view is then
     * responsible for showing a title/app bar with the agent name.
     */
    val hideTitleBar: Boolean = false,
    /** Customize the colors and other appearance of the chat UI. */
    val chatStyle: ChatStyle = ChatStyle(),

    /** Customization of the Conversation that the controller will create. */
    var conversationOptions: ConversationOptions? = null,

    /** Enable Print Transcript actions to show in Menu Bar and at end of conversation */
    var canPrintTranscript: Boolean = false,
    /** Allow the user to manually end a conversation via a UI */
    var canEndConversation: Boolean = false

) : Parcelable {
    @IgnoredOnParcel
    var conversationEventListener: ConversationEventListener? = null
}

class AgentChatController(
    private val agent: Agent,
    private val options: AgentChatControllerOptions
) {
    private var connectedFragment: AgentChatFragment? = null

    fun createFragment(): Fragment {
        return AgentChatFragment().apply {
            arguments = Bundle().apply {
                putParcelable(
                    "args",
                    AgentChatFragmentArgs(agentConfig = agent.config, options = options)
                )
            }
            listener = MainThreadConversationEventListener(options.conversationEventListener)
            controller = this@AgentChatController
        }
    }

    internal fun connectToFragment(fragment: AgentChatFragment) {
        this.connectedFragment = fragment
    }
    fun printTranscript() {
        this.connectedFragment?.printTranscript()
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
    internal var controller: AgentChatController? = null
    /**
     * Flag used to keep track that of whether the web view successfully loaded or not. We only
     * restore state (and avoid reloading the URL) if the last load was successful.
     * */
    internal var pageLoaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // We stash the value of listener and controller in a view model so that when we're recreated we can still
        // get to it and invoke it.
        val viewModel = ViewModelProvider(this)[AgentChatViewModel::class.java]
        if (listener != null) {
            viewModel.listener = listener
        } else {
            listener = viewModel.listener
        }

        if (controller != null) {
            viewModel.controller = controller
        } else {
            controller = viewModel.controller
        }
        controller?.connectToFragment(this)
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
        val chatWebViewClient = ChatWebViewClient(this, agentConfig, requireContext(), args.options, listener)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = generateUserAgent(requireContext())
            webViewClient = chatWebViewClient
            addJavascriptInterface(ChatWebViewInterface(requireContext(), listener), "AndroidSDK")
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
                pageLoaded = true
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
                "agentTransferWaitingMessage" to options.agentTransferWaitingMessage,
                "agentJoinedMessage" to options.agentJoinedMessage,
                "agentLeftMessage" to options.agentLeftMessage,
                "conversationEndedMessage" to options.conversationEndedMessage,
                "chatStyle" to JSONObject(options.chatStyle.toJSON()).toString(),
            )
        ).toString()

        urlBuilder.appendQueryParameter("brand", brandJSON)
        if (options.hideTitleBar) {
            urlBuilder.appendQueryParameter("hideTitleBar", "true")
        }
        val conversationOptions = options.conversationOptions ?: ConversationOptions()
        // The custom greeting was initially a UI-only concept and thus specified via AgentChatControllerOptions,
        // but it now also affects the API, so it's in ConversationOptions. Read it from both places
        // so that old clients don't need to change anything.
        var customGreeting = conversationOptions.customGreeting
        if (customGreeting == null && options.greetingMessage.isNotEmpty()) {
            customGreeting = options.greetingMessage
        }

        val locale = conversationOptions.locale ?: resources.configuration.locales[0]
        urlBuilder.appendQueryParameter("locale", locale.toLanguageTag())
        for ((name, value) in conversationOptions.variables) {
            urlBuilder.appendQueryParameter("variable", "$name:$value")
        }
        for ((name, value) in conversationOptions.secrets) {
            urlBuilder.appendQueryParameter("secret", "$name:$value")
        }
        if (customGreeting != null) {
            urlBuilder.appendQueryParameter("greeting", customGreeting)
        }
        urlBuilder.appendQueryParameter("enableContactCenter", conversationOptions.enableContactCenter.toString())
        if (options.canPrintTranscript) {
            urlBuilder.appendQueryParameter("canPrintTranscript", "true")
        }
        if (options.canEndConversation) {
            urlBuilder.appendQueryParameter("canEndConversation", "true")
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

    fun printTranscript() {
        webView.evaluateJavascript("sierraAndroid.printTranscript()", null)
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
    internal var controller: AgentChatController? = null
}

private class ChatWebViewClient(
    private val fragment: AgentChatFragment,
    private val agentConfig: AgentConfig,
    private val context: Context,
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

private class ChatWebViewInterface(private val context: Context,  private val listener: ConversationEventListener?) {

    @JavascriptInterface
    fun onTransfer(dataJSONStr: String) {
        val dataJSON = try {
            JSONObject(dataJSONStr)
        } catch (e: JSONException) {
            Log.e(TAG, "Cannot parse transfer JSON data", e)
            return
        }
        val isSynchronous = dataJSON.optBoolean("isSynchronous")
        val isContactCenter = dataJSON.optBoolean("isContactCenter")
        val dataArrayJSON = dataJSON.optJSONArray("data")
        val dataMap = mutableMapOf<String, String>()
        if (dataArrayJSON != null) {
            for (i in 0 until dataArrayJSON.length()) {
                val item = dataArrayJSON.getJSONObject(i)
                dataMap[item.getString("key")] = item.getString("value")
            }
        }

        val transfer = ConversationTransfer(isSynchronous, isContactCenter, dataMap)
        listener?.onConversationTransfer(transfer)
    }

    private fun createWebPrintJob(webView: WebView) {
        (this.context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { printManager ->
            val jobName = "Chat Transcript"
            val printAdapter = webView.createPrintDocumentAdapter(jobName)

            printManager.print(
                jobName,
                printAdapter,
                PrintAttributes.Builder().build()
            )
        }
    }
    @JavascriptInterface
    fun onPrint(url: String, data: String) {
        var heldWebView: WebView? = null
        fun doWebViewPrint() {
            // Create a WebView object specifically for printing
            val webView = WebView(this.context)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                override fun onPageFinished(view: WebView, url: String) {
                    createWebPrintJob(view)
                    heldWebView = null
                }
            }

            webView.postUrl(url, data.toByteArray())
            // Keep a reference to WebView object until you pass the PrintDocumentAdapter
            // to the PrintManager
            heldWebView = webView
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            doWebViewPrint()
        }
    }

    @JavascriptInterface
    fun onAgentMessageEnd() {
        listener?.onAgentMessageEnd()
    }
}

private const val TAG = "AgentChatController"
