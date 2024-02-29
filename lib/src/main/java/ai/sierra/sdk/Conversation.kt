// Copyright Sierra

package ai.sierra.sdk

import android.os.Handler
import android.os.Looper
import android.os.Parcelable
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
) : Parcelable

interface ConversationEventListener {
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
}

/**
 * Wrapper class that takes a passed in ConversationEventListener and ensures that we only invoke
 * its methods on the main thread (so that calling code does not have to concern itself with this).
 */
internal class MainThreadConversationEventListener(private val listener: ConversationEventListener?) : ConversationEventListener {
    private val handler = Handler(Looper.getMainLooper())

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
}

data class ConversationTransfer(
    val isSynchronous: Boolean,
    val data: Map<String, String>
)
