// Copyright Sierra

package ai.sierra.sdk

import java.util.Locale

/**
 * Configuration options when creating a new conversation.
 *
 * @property variables Initial values for variables (possible variables are agent-specific).
 * @property secrets Initial values for secrets (possible variables are agent-specific).
 * @property locale Locale to use for the chat conversation, if the agent is multi-lingual.
 * If not specified, the device's locale will be used.
 * @property customGreeting Custom greeting that the agent uses before interacting with the user.
 */
data class ConversationOptions(
    val variables: Map<String, String>? = null,
    val secrets: Map<String, String>? = null,
    val locale: Locale? = null,
    val customGreeting: String? = null
)