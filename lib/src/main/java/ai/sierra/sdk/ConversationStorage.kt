// Copyright Sierra

package ai.sierra.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages conversation state storage with pluggable backing stores based on persistence mode.
 *
 * - NONE: No storage, all operations are no-ops
 * - MEMORY: In-memory cache only, state lost on app restart
 * - DISK: In-memory cache backed by SharedPreferences, state survives app restart
 */
class ConversationStorage internal constructor(
    private val mode: PersistenceMode,
    private val storageKey: String,
    private val context: Context?
) {
    private val cache = mutableMapOf<String, String>()
    private val prefs: SharedPreferences? = if (mode == PersistenceMode.DISK && context != null) {
        context.getSharedPreferences(storageKey, Context.MODE_PRIVATE)
    } else null

    init {
        // Load from disk on init if DISK mode
        if (mode == PersistenceMode.DISK) {
            prefs?.all?.forEach { (key, value) ->
                if (value is String) cache[key] = value
            }
        }
    }

    /**
     * Get a value from storage.
     * @param key The key to look up
     * @return The stored value, or null if not found or in NONE mode
     */
    fun getItem(key: String): String? {
        if (mode == PersistenceMode.NONE) return null
        return cache[key]
    }

    /**
     * Store a value.
     * @param key The key to store under
     * @param value The value to store
     */
    fun setItem(key: String, value: String) {
        if (mode == PersistenceMode.NONE) return
        cache[key] = value
        if (mode == PersistenceMode.DISK) {
            prefs?.edit()?.putString(key, value)?.apply()
        }
    }

    /**
     * Clear all stored values.
     */
    fun clear() {
        cache.clear()
        if (mode == PersistenceMode.DISK) {
            prefs?.edit()?.clear()?.apply()
        }
    }

    /**
     * Get all stored values as a map.
     * @return A copy of all stored key-value pairs
     */
    fun getAll(): Map<String, String> = cache.toMap()
}
