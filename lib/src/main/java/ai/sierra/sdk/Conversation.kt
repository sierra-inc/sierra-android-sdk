// Copyright Sierra

package ai.sierra.sdk

import android.net.http.SslError
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.webkit.SslErrorHandler
import android.webkit.WebView
import kotlinx.parcelize.Parcelize
import java.util.Locale

/** Configuration options when creating a new conversation. */
@Parcelize
data class ConversationOptions(
    /** Initial values for variables (possible variables are agent-specific). */
    val variables: Map<String, String> = emptyMap(),
    /** Initial values for secrets (possible variables are agent-specific). */
    val secrets: Map<String, String> = emptyMap(),
    /**
     * Locale to use for the chat conversation, if the agent is multi-lingual. If not specified,
     * the device's locale will be used.
     */
    val locale: Locale? = null,
    /**
     * Custom greeting that the agent has used (before it has interacted with
     * the user).
     */
    val customGreeting: String? = null,
    /**
     * Enables contact center integration for this agent. Only has an effect for agents where the
     * integration is controlled per-conversation (as opposed to being globally enabled or disabled).
     */
    val enableContactCenter: Boolean? = false,
) : Parcelable

/**
 * Result type for secret expiry callback responses.
 */
sealed class SecretExpiryResult {
    /** A new value for the secret. */
    data class Success(val value: String?) : SecretExpiryResult()
    /** An error occurred while fetching the secret; the request may be retried. */
    data class Error(val message: String) : SecretExpiryResult()
}

interface ConversationEventListener : AgentEventListener {
    /**
     * Callback invoked when the embedded web UI is ready to be displayed.
     *
     * This fires before the first agent message is necessarily rendered and may fire before a
     * conversation ID is available.
     *
     * @param isNewConversation True when the chat opened a new conversation rather than resuming
     * an existing one.
     */
    fun onOpen(isNewConversation: Boolean) {}

    /**
     * Callback invoked on the main thread when the agent chat encounters a critical error and
     * cannot begin the conversation.
     */
    fun onConversationInitializationError() {}

    /**
     * Callback invoked when the user chatting with the virtual agent has requested a transfer to an
     * external agent.
     */
    fun onConversationTransfer(transfer: ConversationTransfer) {}

    /**
     * Callback invoked when a non-Sierra agent joins the conversation.
     */
    fun onExternalAgentJoin(externalConversationID: String?, externalAgentID: String?) {}

    /**
     * Callback invoked when a conversation starts.
     *
     * @param conversationID The unique identifier for the conversation.
     */
    fun onConversationStart(conversationID: String) {}

    /**
     * Callback invoked when the virtual agent finishes replying to the user.
     * Not invoked for the greeting message.
     */
    fun onAgentMessageEnd() {}

    /**
     * Callback invoked when the conversation ends.
     */
    fun onConversationEnded() {}

    /**
     * Callback invoked when the conversation list is shown.
     */
    fun onShowConversationList() {}

    /**
     * Callback invoked when the conversation list is hidden (a conversation is opened or started).
     */
    fun onHideConversationList() {}

    /**
     * Callback invoked on the main thread when the embedded WebView receives an SSL error.
     *
     * The default implementation cancels the request and should be sufficient for all normal and
     * production uses of the SDK.
     *
     * Overrides and any non-default behavior should be considered only in controlled testing
     * situations, such as local development against a test certificate. Production applications
     * should continue using the default behavior.
     */
    fun onReceivedSslError(view: WebView?, sslErrorHandler: SslErrorHandler?, error: SslError?) {
        sslErrorHandler?.cancel()
    }

}

/**
 * Wrapper class that takes a passed in ConversationEventListener and ensures that we only invoke
 * its methods on the main thread (so that calling code does not have to concern itself with this).
 */
internal class MainThreadConversationEventListener(private val listener: ConversationEventListener?) : ConversationEventListener {
    private val handler = Handler(Looper.getMainLooper())

    override fun onOpen(isNewConversation: Boolean) {
        handler.post {
            listener?.onOpen(isNewConversation)
        }
    }

    override fun onConversationInitializationError() {
        handler.post {
            listener?.onConversationInitializationError()
        }
    }

    override fun onConversationTransfer(transfer: ConversationTransfer) {
        handler.post {
            listener?.onConversationTransfer(transfer)
        }
    }

    override fun onExternalAgentJoin(externalConversationID: String?, externalAgentID: String?) {
        handler.post {
            listener?.onExternalAgentJoin(externalConversationID, externalAgentID)
        }
    }

    override fun onConversationStart(conversationID: String) {
        handler.post {
            listener?.onConversationStart(conversationID)
        }
    }

    override fun onAgentMessageEnd() {
        handler.post {
            listener?.onAgentMessageEnd()
        }
    }

    override fun onConversationEnded() {
        handler.post {
            listener?.onConversationEnded()
        }
    }

    override fun onSecretExpiry(secretName: String, replyHandler: (SecretExpiryResult) -> Unit) {
        handler.post {
            if (listener != null) {
                listener.onSecretExpiry(secretName, replyHandler)
            } else {
                replyHandler(SecretExpiryResult.Success(null))
            }
        }
    }

    override fun onUserIdentityTokenExpiry(replyHandler: (SecretExpiryResult) -> Unit) {
        handler.post {
            if (listener != null) {
                listener.onUserIdentityTokenExpiry(replyHandler)
            } else {
                replyHandler(SecretExpiryResult.Success(null))
            }
        }
    }

    override fun onShowConversationList() {
        handler.post {
            listener?.onShowConversationList()
        }
    }

    override fun onHideConversationList() {
        handler.post {
            listener?.onHideConversationList()
        }
    }

    override fun onReceivedSslError(view: WebView?, sslErrorHandler: SslErrorHandler?, error: SslError?) {
        val listener = listener ?: run {
            sslErrorHandler?.cancel()
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onReceivedSslError(view, sslErrorHandler, error)
            return
        }

        this.handler.post {
            listener.onReceivedSslError(view, sslErrorHandler, error)
        }
    }

    override fun onLinkClick(url: Uri): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "onLinkClick must be called on the main thread"
        }
        return listener?.onLinkClick(url) ?: false
    }
}

data class ConversationTransfer(
    /**
     * True if a synchronous transfer was requested, and the user expects the
     * conversation to continue immediately.
     */
    val isSynchronous: Boolean,
    /**
     * True if the transfer was handled by a Sierra Contact Center integration,
     * and the conversation with the human agent will continue in the same chat.
     */
    val isContactCenter: Boolean,
    /**
     * Additional (customer-specific) data, to allow a hand-off from the virtual
     * agent to the external agent.
     */
    val data: Map<String, String>
)
