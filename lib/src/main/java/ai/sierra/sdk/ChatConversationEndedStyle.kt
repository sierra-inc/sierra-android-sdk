// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** Optional layout for an ended conversation. Omitted properties keep their defaults. */
@Parcelize
data class ChatConversationEndedStyle(
    /** Logical alignment of the ended message. */
    val messageAlignment: MessageAlignment? = null,
    /** Keep the composer background, border, sizing, and insets. Defaults to true. */
    val showComposerContainer: Boolean? = null,
    /** Space above the new-chat action, in density-independent pixels. Clamped to 0-320. */
    val actionSpacing: Int? = null,
    /** Style of this conversation's new-chat button, separate from the list button. */
    val newChatButtonStyle: ChatButtonStyle? = null,
    /** Show the conversation disclosure after the conversation ends. Defaults to true. */
    val showDisclosure: Boolean? = null,
) : Parcelable {
    enum class MessageAlignment(val value: String) {
        START("start"), CENTER("center"), END("end")
    }

    internal fun toJSONString(): String? {
        val json = mutableMapOf<String, Any?>()
        messageAlignment?.let { json["messageAlignment"] = it.value }
        showDisclosure?.let { json["showDisclosure"] = it }
        showComposerContainer?.let { json["showComposerContainer"] = it }
        actionSpacing?.let { json["actionSpacing"] = it }
        newChatButtonStyle?.toJSON()?.takeIf { it.isNotEmpty() }?.let {
            json["newChatButtonStyle"] = it
        }
        return if (json.isEmpty()) null else JSONObject(json as Map<*, *>).toString()
    }
}
