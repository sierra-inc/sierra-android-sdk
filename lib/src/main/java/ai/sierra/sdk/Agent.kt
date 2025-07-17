// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for an agent in the SDK.
 *
 * @property token Token that identifies the agent.
 * @property apiHost Optional override for the Sierra API endpoint; if not provided, a default is used.
 */
@Parcelize
data class AgentConfig(
    val token: String,
    val target: String? = null,
    var apiHost: AgentAPIHost = AgentAPIHost.PROD
): Parcelable {
    internal val url get() = "https://${apiHost.hostname}/agent/${token}/mobile"
}
enum class AgentAPIHost(val hostname: String, val displayName: String) {
    PROD("sierra.chat", "Prod"),
    EU("eu.sierra.chat", "EU"),
    STAGING("staging.sierra.chat", "Staging"),
    LOCAL("chat.sierra.codes:8083", "Local")
}

class Agent(internal val config: AgentConfig) {

}
