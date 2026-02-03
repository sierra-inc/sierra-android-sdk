// Copyright Sierra

package ai.sierra.sdk

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for an agent in the SDK.
 *
 * @property token Token that identifies the agent.
 * @property target Optional target for the agent release.
 * @property apiHost Optional override for the Sierra API endpoint; if not provided, a default is used.
 * @property persistence Persistence mode for conversation state. Defaults to MEMORY for backwards compatibility.
 */
@Parcelize
data class AgentConfig(
    val token: String,
    val target: String? = null,
    var apiHost: AgentAPIHost = AgentAPIHost.PROD,
    val persistence: PersistenceMode = PersistenceMode.MEMORY
): Parcelable {
    internal val url get() = "https://${apiHost.hostname}/agent/${token}/mobile"
}

enum class AgentAPIHost(val hostname: String, val displayName: String) {
    PROD("sierra.chat", "Prod"),
    EU("eu.sierra.chat", "EU"),
    SG("sg.sierra.chat", "SG"),
    STAGING("staging.sierra.chat", "Staging"),
    LOCAL("chat.sierra.codes:8083", "Local")
}

/**
 * Main entry point for the Sierra SDK.
 *
 * @param config Configuration for the agent.
 * @param context Application context, required for [PersistenceMode.DISK] mode.
 *                Pass `applicationContext` to avoid memory leaks.
 * @throws IllegalArgumentException if [PersistenceMode.DISK] is requested without providing a context.
 */
class Agent(
    internal val config: AgentConfig,
    context: Context? = null
) {
    init {
        if (config.persistence == PersistenceMode.DISK && context == null) {
            throw IllegalArgumentException(
                "Context is required for PersistenceMode.DISK. " +
                "Either provide applicationContext, or use PersistenceMode.MEMORY or PersistenceMode.NONE."
            )
        }
    }

    private val storage: ConversationStorage = ConversationStorage(
        mode = config.persistence,
        storageKey = "sierra_chat_${config.token}",
        context = context?.applicationContext
    )

    fun getStorage(): ConversationStorage = storage

    /**
     * Clears any stored conversation state, causing the next chat session to start fresh.
     */
    fun resetConversation() {
        storage.clear()
    }
}
