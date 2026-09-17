// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.annotation.RestrictTo
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** Optional overrides for a chat button. Dimensions use CSS strings with px, em, rem, or % units, except borderWidth cannot use %. */
@Parcelize
data class ChatButtonStyle(
    val alignment: Alignment? = null,
    @ColorInt val backgroundColor: Int? = null,
    @ColorInt val textColor: Int? = null,
    @ColorInt val borderColor: Int? = null,
    val borderWidth: String? = null,
    val height: String? = null,
    val width: String? = null,
    val padding: String? = null,
    val borderRadius: String? = null,
    /** Decorative SVG before the label. Sanitized by the shared renderer. */
    val iconSVG: String? = null,
) : Parcelable {
    enum class Alignment(val value: String) { START("start"), CENTER("center"), END("end") }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun toJSON(): Map<String, Any> = listOf(
        "alignment" to alignment?.value,
        "backgroundColor" to backgroundColor?.toHexColor(),
        "textColor" to textColor?.toHexColor(),
        "borderColor" to borderColor?.toHexColor(),
        "borderWidth" to borderWidth,
        "height" to height,
        "width" to width,
        "padding" to padding,
        "borderRadius" to borderRadius,
        "iconSVG" to iconSVG,
    ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun toJSONString(): String? = toJSON().takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() }
}
