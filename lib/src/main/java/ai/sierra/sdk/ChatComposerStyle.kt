// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.os.Parcelable
import androidx.annotation.RestrictTo
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/**
 * Insets for one edge box of the message composer, in density-independent pixels. `start` and
 * `end` are logical, so one set of insets is correct in both layout directions.
 */
@Parcelize
data class ChatComposerInsets(
    /** Inset from the top edge. */
    val top: Int? = null,

    /** Inset from the leading edge (left in LTR, right in RTL). */
    val start: Int? = null,

    /** Inset from the bottom edge. */
    val bottom: Int? = null,

    /** Inset from the trailing edge (right in LTR, left in RTL). */
    val end: Int? = null,
) : Parcelable {
    internal fun toJSON(): Map<String, Any?> {
        val json = mutableMapOf<String, Any?>()
        top?.let { json["top"] = it }
        start?.let { json["start"] = it }
        bottom?.let { json["bottom"] = it }
        end?.let { json["end"] = it }
        return json
    }
}

/**
 * Layout overrides for the message composer (the text input and its action buttons).
 *
 * Every property is optional and overrides only its own default, so an omitted
 * `ChatComposerStyle` leaves the composer unchanged. Supplying `outerInsets` insets the composer
 * from the edges of the chat and moves the input background onto the composer itself, so the
 * inset gutter shows the chat background.
 *
 * Composer colors stay on [ChatStyleColors]: `inputBackground`, `inputBorder`,
 * `inputFocusBorder`, `inputText`, and `inputPlaceholder`.
 */
@Parcelize
data class ChatComposerStyle(
    /** Space between the edges of the chat and the composer. */
    val outerInsets: ChatComposerInsets? = null,

    /**
     * Space between the composer's edges and its content. Replaces the default padding around
     * the text input and its action buttons.
     */
    val contentInsets: ChatComposerInsets? = null,

    /** Minimum height of the composer. */
    val minimumHeight: Int? = null,

    /** Number of lines the text input grows to before it starts scrolling. */
    val maximumLines: Int? = null,

    /** Corner radius of the composer. */
    val cornerRadius: Int? = null,

    /** Width of the composer's border. Drawn in `ChatStyleColors.inputBorder`. */
    val borderWidth: Int? = null,

    /** Width and height of the send and upload buttons. */
    val actionButtonSize: Int? = null,
) : Parcelable {
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun toJSON(): Map<String, Any?> {
        // Match the web embed's ChatComposerStyle shape.
        val json = mutableMapOf<String, Any?>()
        outerInsets?.let { json["outerInsets"] = it.toJSON() }
        contentInsets?.let { json["contentInsets"] = it.toJSON() }
        minimumHeight?.let { json["minimumHeight"] = it }
        maximumLines?.let { json["maximumLines"] = it }
        cornerRadius?.let { json["cornerRadius"] = it }
        borderWidth?.let { json["borderWidth"] = it }
        actionButtonSize?.let { json["actionButtonSize"] = it }
        return json
    }

    /**
     * The value of the `composerStyle` query parameter, or null when nothing is configured and
     * the parameter should be omitted so the embed keeps its defaults.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun toJSONString(): String? {
        val json = toJSON()
        if (json.isEmpty()) {
            return null
        }
        return JSONObject(json as Map<*, *>).toString()
    }
}
