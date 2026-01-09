// Copyright Sierra

package ai.sierra.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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

    /**
     * Use chat interface strings configured on the server (greeting, error messages, etc.).
     * When enabled, server-configured strings take precedence over local string options.
     */
    val useConfiguredChatStrings: Boolean = false,

    /**
     * Use styling configured on the server (colors, typography, logo, etc.).
     * When enabled, server-configured styles take precedence over local chatStyle.
     */
    val useConfiguredStyle: Boolean = false,

    /**
     * Message shown from the agent when starting the conversation.
     * Overridden by server-configured greeting message if useConfiguredChatStrings is true.
     */
    var greetingMessage: String = "How can I help you today?",

    /**
     * Secondary text to display above the agent message at the start of a conversation.
     * Overridden by server-configured disclosure if useConfiguredChatStrings is true.
     */
    var disclosure: String? = null,

    /**
     * Message shown when an error is encountered during the conversation.
     * Overridden by server-configured error message if useConfiguredChatStrings is true.
     */
    var errorMessage: String = "Oops, an error was encountered! Please try again.",

    /**
     * Placeholder value displayed in the chat input when it is empty.
     * Overridden by server-configured input placeholder if useConfiguredChatStrings is true.
     * Defaults to "Message…" when this value is empty.
     */
    var inputPlaceholder: String = "",

    /**
     * Message shown in place of the chat input when the conversation has ended.
     * Overridden by server-configured ended message if useConfiguredChatStrings is true.
     * Defaults to "Chat ended" when this value is empty.
     */
    var conversationEndedMessage: String = "",

    /**
     * Message shown when waiting for a human agent to join the conversation.
     * Overridden by server-configured waiting message if useConfiguredChatStrings is true.
     */
    var agentTransferWaitingMessage: String = "Waiting for agent…",

    /**
     * Message shown when a human agent has joined the conversation.
     * Overridden by server-configured joined message if useConfiguredChatStrings is true.
     */
    var agentJoinedMessage: String = "Agent connected",

    /**
     * Message shown when a human agent has left the conversation.
     * Overridden by server-configured left message if useConfiguredChatStrings is true.
     */
    var agentLeftMessage: String = "Agent disconnected",

    /**
     * Customize the colors and other appearance of the chat UI.
     * Overridden by server-configured chat style if useConfiguredStyle is true.
     */
    val chatStyle: ChatStyle = ChatStyle(),

    /**
     * Hide the title bar in the fragment that the controller creates. The containing view is then
     * responsible for showing a title/app bar with the agent name.
     */
    val hideTitleBar: Boolean = false,

    /** Customization of the Conversation that the controller will create. */
    var conversationOptions: ConversationOptions? = null,

    /** Enable Print Transcript actions to show in Menu Bar and at end of conversation */
    var canPrintTranscript: Boolean = false,
    /** Allow the user to manually end a conversation via a UI */
    var canEndConversation: Boolean = false,
    /** Allow the user to start a new conversation via a UI */
    var canStartNewChat: Boolean = false,

    /**
     * Enable automatic state restoration when navigating away and back.
     * When enabled, state state and conversation history will be preserved
     * across navigation (e.g. when using NavController).
     *
     * This is fully automatic - no additional code required.
     * Just ensure your ViewModel holding the AgentChatController survives navigation
     * (e.g. using activityViewModels instead of viewModels).
     */
    var enableAutoStateRestoration: Boolean = false,

    /**
     * Start the chat with messages at the top of the chat frame, allowing the
     * conversation to expand downward until the frame height has been reached,
     * at which point older messages scroll out of view.
     */
    var startAtTop: Boolean = false,

    /**
     * Pin the disclosure text to the top of the chat frame so that it is
     * visible throughout the conversation.
     */
    var pinDisclosure: Boolean = false

) : Parcelable {
    @IgnoredOnParcel
    var conversationEventListener: ConversationEventListener? = null
}

class AgentChatController(
    private val agent: Agent,
    private val options: AgentChatControllerOptions
) {
    private var connectedFragment: AgentChatFragment? = null
    private var savedFragmentState: Fragment.SavedState? = null

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

            // Consume saved state
            if (savedFragmentState != null) {
                setInitialSavedState(savedFragmentState)
                savedFragmentState = null
            }
        }
    }

    internal fun saveFragmentState(fragmentManager: FragmentManager, fragment: Fragment) {
        if (options.enableAutoStateRestoration) {
            savedFragmentState = fragmentManager.saveFragmentInstanceState(fragment)
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

    /**
     * Storage object for the web view (since sessionStorage is not persisted
     * when the Fragment that contains the WebView is recreated). Will be saved
     * and restored from the Bundle.
     */
    internal var storage = mutableMapOf<String, String>()

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
        val chatWebViewClient =
            ChatWebViewClient(this, agentConfig, requireContext(), args.options, listener)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = generateUserAgent(requireContext())
            webViewClient = chatWebViewClient
            addJavascriptInterface(
                ChatWebViewInterface(requireContext(), storage, listener),
                "AndroidSDK"
            )
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
                val savedStorage =
                    savedInstanceState.getSerializable("storage") as? HashMap<String, String>
                if (savedStorage != null) {
                    storage.putAll(savedStorage)
                } else {
                    storage.clear()
                }
                webView.restoreState(savedInstanceState)
                return
            }
        }

        // Ensure that there's no chat state from previous runs still present.
        storage.clear()

        val agentConfig = args.agentConfig
        val options = args.options
        // Turn config and options into query parameters that android.tsx expects.
        val urlBuilder = Uri.parse(agentConfig.url).buildUpon()
        if (agentConfig.target != null && agentConfig.target.isNotEmpty()) {
            urlBuilder.appendQueryParameter("target", agentConfig.target)
        }

        // Should match the Brand type from bots/useChat.tsx
        val brandJSON = JSONObject(
            mapOf(
                "botName" to options.name,
                "greetingMessage" to options.greetingMessage,
                "errorMessage" to options.errorMessage,
                "agentTransferWaitingMessage" to options.agentTransferWaitingMessage,
                "agentJoinedMessage" to options.agentJoinedMessage,
                "agentLeftMessage" to options.agentLeftMessage,
                "chatStyle" to JSONObject(options.chatStyle.toJSON()).toString(),
            )
        ).toString()

        urlBuilder.appendQueryParameter("brand", brandJSON)

        // Subset of the ChatUiStrings type from chat/ui-strings.ts
        val chatInterfaceStrings = JSONObject(
            mapOf(
                "inputPlaceholder" to options.inputPlaceholder,
                "disclosure" to (options.disclosure ?: ""),
                "conversationEndedMessage" to options.conversationEndedMessage,
            )
        ).toString()
        urlBuilder.appendQueryParameter("chatInterfaceStrings", chatInterfaceStrings)

        if (options.hideTitleBar) {
            urlBuilder.appendQueryParameter("hideTitleBar", "true")
        }
        urlBuilder.appendQueryParameter("persistenceMode", "custom")
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
        urlBuilder.appendQueryParameter(
            "enableContactCenter",
            conversationOptions.enableContactCenter.toString()
        )
        if (options.canPrintTranscript) {
            urlBuilder.appendQueryParameter("canPrintTranscript", "true")
        }
        if (options.canEndConversation) {
            urlBuilder.appendQueryParameter("canEndConversation", "true")
        }
        if (options.canStartNewChat) {
            urlBuilder.appendQueryParameter("canStartNewChat", "true")
        }
        if (options.startAtTop) {
            urlBuilder.appendQueryParameter("startAtTop", "true")
        }
        if (options.pinDisclosure) {
            urlBuilder.appendQueryParameter("pinDisclosure", "true")
        }
        if (options.useConfiguredChatStrings) {
            urlBuilder.appendQueryParameter("useConfiguredChatStrings", "true")
        }
        if (options.useConfiguredStyle) {
            urlBuilder.appendQueryParameter("useConfiguredStyle", "true")
        }

        val url = urlBuilder.build().toString()
        webView.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putBoolean("pageLoaded", pageLoaded)
        outState.putSerializable("storage", HashMap(storage))
        val args = arguments?.getParcelable<AgentChatFragmentArgs>("args")
        if (args != null) {
            outState.putParcelable("args", args)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.saveFragmentState(parentFragmentManager, this)
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

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val baseUri = Uri.parse(agentConfig.url)

        if (request.isForMainFrame && (url.host != baseUri.host || url.scheme != baseUri.scheme)) {
            Log.i(TAG, "External URL ($url) loaded, will open in the browser")

            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Ensures it works in non-Activity contexts
            }

            val context = view?.context ?: return false
            Handler(Looper.getMainLooper()).post {
                context.startActivity(intent)
            }
            return true
        }
        return false
    }
}

private class ChatWebViewInterface(
    private val context: Context,
    private val storage: MutableMap<String, String>,
    private val listener: ConversationEventListener?
) {

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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    false

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

    @JavascriptInterface
    fun onEndChat() {
        listener?.onConversationEnded()
    }

    @JavascriptInterface
    fun storeValue(key: String, value: String) {
        storage.put(key, value)
    }

    @JavascriptInterface
    fun getStoredValue(key: String): String? {
        return storage.get(key)
    }

    @JavascriptInterface
    fun clearStorage() {
        storage.clear()
    }
}

private const val TAG = "AgentChatController"
