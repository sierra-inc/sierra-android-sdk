// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcelable
import androidx.annotation.RestrictTo
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** An optional text-message action above the composer. */
@Parcelize
data class MessageInputPresetAction(
    val label: String,
    val clientEvent: ClientEvent,
    val showAfterAgentMessageCount: Int? = null,
    val style: ChatButtonStyle? = null,
) : Parcelable {
    @Parcelize
    data class ClientEvent(val message: Message) : Parcelable {
        @Parcelize
        data class Message(val content: String) : Parcelable
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun toJSONString(): String {
        val json = mutableMapOf<String, Any>(
            "label" to label,
            "clientEvent" to mapOf("type" to "message", "message" to mapOf("content" to clientEvent.message.content)),
        )
        showAfterAgentMessageCount?.let { json["showAfterAgentMessageCount"] = it }
        style?.let { json["style"] = it.toJSON() }
        return JSONObject(json as Map<*, *>).toString()
    }
}
