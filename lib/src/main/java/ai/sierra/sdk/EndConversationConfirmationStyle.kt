// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** Style overrides for inline end-conversation confirmation. Null values keep their defaults. */
@Parcelize
data class EndConversationConfirmationStyle(
    val showFooterDivider: Boolean? = null,
    val confirmButton: ChatButtonStyle? = null,
    val cancelButton: ChatButtonStyle? = null,
) : Parcelable {
    internal fun toJSONString(): String? {
        val json = mutableMapOf<String, Any?>()
        showFooterDivider?.let { json["showFooterDivider"] = it }
        confirmButton?.toJSON()?.takeIf { it.isNotEmpty() }?.let { json["confirmButton"] = it }
        cancelButton?.toJSON()?.takeIf { it.isNotEmpty() }?.let { json["cancelButton"] = it }
        return if (json.isEmpty()) null else JSONObject(json).toString()
    }
}
