// Copyright Sierra

package ai.sierra.sdk

/**
 * Persistence mode for conversation state.
 *
 * Controls how conversation state is stored and whether it survives various lifecycle events.
 */
enum class PersistenceMode {
    /**
     * No persistence. Conversation state is lost when the chat view is destroyed.
     */
    NONE,

    /**
     * In-memory persistence. Conversation survives navigation and configuration
     * changes, but is lost on app restart.
     */
    MEMORY,

    /**
     * Disk persistence. Conversation survives app restart.
     * Data is stored in the app's private SharedPreferences.
     *
     * Requires an application Context to be provided when creating the Agent.
     */
    DISK
}
