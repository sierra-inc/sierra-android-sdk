// Copyright Sierra

package ai.sierra.sdk

/**
 * Configuration for an agent in the SDK.
 *
 * @property token Token that identifies the agent.
 * @property apiHost Optional override for the Sierra API endpoint; if not provided, a default is used.
 */
data class AgentConfig(
    val token: String,
    var apiHost: AgentAPIHost = AgentAPIHost.PROD
) {
    internal val url get() = "https://${apiHost.hostname}/agent/${token}/chat"
}
enum class AgentAPIHost(val hostname: String, val displayName: String) {
    PROD("sierra.chat", "Prod"),
    STAGING("staging.sierra.chat", "Staging"),
    LOCAL("chat.sierra.codes:8083", "Local")
}

class Agent(internal val config: AgentConfig) {

}