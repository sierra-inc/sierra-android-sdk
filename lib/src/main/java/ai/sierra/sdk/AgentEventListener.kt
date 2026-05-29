// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri

/**
 * Callbacks for events that the agent runtime can emit during either a chat or voice conversation.
 *
 * Implement this once and supply it through the chat, voice, or unified coordinator surfaces so the
 * same logic handles shared agent-runtime events.
 */
interface AgentEventListener {
    /**
     * Callback invoked when a secret needs to be refreshed. The replyHandler should be invoked with
     * one of:
     * - SecretExpiryResult.Success(newValue) - a new value for the secret
     * - SecretExpiryResult.Success(null) - if the secret cannot be provided due to a known condition
     * - SecretExpiryResult.Error(message) - if the secret cannot be fetched right now, but the
     *   request should be retried
     *
     * @param secretName The name of the secret that needs refreshing
     * @param replyHandler Function to call with the refresh result
     */
    fun onSecretExpiry(secretName: String, replyHandler: (SecretExpiryResult) -> Unit) {
        replyHandler(SecretExpiryResult.Success(null))
    }

    /**
     * Callback invoked when the user identity token (JWT) has expired and needs to be refreshed.
     * The replyHandler should be invoked with one of:
     * - SecretExpiryResult.Success(freshToken) - a fresh JWT string
     * - SecretExpiryResult.Success(null) - if the token cannot be provided (the session downgrades
     *   to anonymous)
     * - SecretExpiryResult.Error(message) - if the token cannot be fetched right now, but the
     *   request should be retried
     *
     * @param replyHandler Function to call with the refresh result
     */
    fun onUserIdentityTokenExpiry(replyHandler: (SecretExpiryResult) -> Unit) {
        replyHandler(SecretExpiryResult.Success(null))
    }

    /**
     * Callback invoked on the main thread when the customer taps a link in chat or in a voice
     * attachment. Return `true` if the host app handled the link in-app, or `false` to let the SDK
     * fall back to `Intent.ACTION_VIEW`.
     */
    fun onLinkClick(url: Uri): Boolean = false
}
