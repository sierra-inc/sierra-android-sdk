// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

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
    val persistence: PersistenceMode = PersistenceMode.MEMORY,
    /** Headless API token required for SVP voice connections. Not needed for chat. */
    val headlessAPIToken: String? = null
): Parcelable {
    internal val url get() = "${apiHost.embedBaseURL}/agent/${token}/mobile"
}

enum class AgentAPIHost(val hostname: String, val displayName: String) {
    PROD("sierra.chat", "Prod"),
    EU("eu.sierra.chat", "EU"),
    SG("sg.sierra.chat", "SG"),
    JP("jp.sierra.chat", "JP"),
    AU("au.sierra.chat", "AU"),
    STAGING("staging.sierra.chat", "Staging"),
    LOCAL("chat.sierra.codes:8083", "Local");

    @SierraInternalApi
    public val apiBaseURL: String
        get() = when (this) {
            PROD -> "https://api.sierra.chat"
            EU -> "https://eu.api.sierra.chat"
            SG -> "https://sg.api.sierra.chat"
            JP -> "https://jp.api.sierra.chat"
            AU -> "https://au.api.sierra.chat"
            STAGING -> "https://api-staging.sierra.chat"
            LOCAL -> "https://api.sierra.codes:8083"
        }

    @SierraInternalApi
    public val embedBaseURL: String
        get() = when (this) {
            PROD -> "https://sierra.chat"
            EU -> "https://eu.sierra.chat"
            SG -> "https://sg.sierra.chat"
            JP -> "https://jp.sierra.chat"
            AU -> "https://au.sierra.chat"
            STAGING -> "https://staging.sierra.chat"
            LOCAL -> "https://chat.sierra.codes:8083"
        }
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
    @property:SierraInternalApi
    public val config: AgentConfig,
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
        storageKey = ConversationStorage.storageKeyForToken(config.token),
        context = context?.applicationContext
    )

    fun getStorage(): ConversationStorage = storage

    /**
     * Clears any stored conversation state, causing the next chat session to start fresh.
     */
    fun resetConversation() {
        storage.clear()
    }

    companion object {
        /**
         * Clears any stored conversation state for the given token without requiring an Agent instance.
         * Useful for clearing DISK-mode storage before the Agent is created (e.g., on logout).
         *
         * @param token The agent token whose conversation state should be cleared.
         * @param context Application context used to access SharedPreferences.
         */
        fun resetConversation(token: String, context: Context) {
            val key = ConversationStorage.storageKeyForToken(token)
            context.getSharedPreferences(key, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
