// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.annotation.ColorInt
import androidx.annotation.RestrictTo

/**
 * Customize the colors and other appearance of the chat UI. When useConfiguredStyle is true in
 * AgentChatControllerOptions, these settings are overridden by server-configured settings.
 */
@Parcelize
data class ChatStyle (
    val colors: ChatStyleColors = ChatStyleColors(),
    val typography: ChatStyleTypography? = null
): Parcelable {
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun toJSON(): Map<String, Any> {
        // Match the web embed's ChatStyle shape.
        val json = mutableMapOf<String, Any>(
            "colors" to colors.toJSON()
        )
        // Serialize as "type" to match the web embed's ChatStyle shape.
        typography?.let {
            json["type"] = it.toJSON()
        }
        return json
    }
}

/**
 * Typography settings for chat UI. When useConfiguredStyle is true in AgentChatControllerOptions,
 * these settings are overridden by server-configured typography.
 */
@Parcelize
data class ChatStyleTypography(
    /**
     * The font family, a comma-separated list of font names.
     * Note: Only built-in system fonts are supported. Custom fonts loaded by the app are not available.
     */
    val fontFamily: String? = null,

    /** The font size, in pixels. */
    val fontSize: Int? = null,

    /** Typography overrides for chat bubbles from the user. */
    val userBubble: ChatTextStyle? = null,

    /** Typography overrides for chat bubbles from the AI assistant. */
    val assistantBubble: ChatTextStyle? = null,

    /** Typography overrides for the title bar text. */
    val titleBar: ChatTextStyle? = null,

    /** Typography overrides for the disclosure (disclaimer) text. */
    val disclosure: ChatTextStyle? = null,

    /** Typography overrides for the message input text. */
    val messageInput: ChatTextStyle? = null,
) : Parcelable {
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun toJSON(): Map<String, Any?> {
        val typography = mutableMapOf<String, Any?>()
        fontFamily?.let { typography["fontFamily"] = it }
        fontSize?.let {
            typography["fontSize"] = it
            // Set all responsive font sizes
            typography["fontSize900"] = it
            typography["fontSize750"] = it
            typography["fontSize500"] = it
        }
        userBubble?.let { typography["userBubble"] = it.toJSON() }
        assistantBubble?.let { typography["assistantBubble"] = it.toJSON() }
        titleBar?.let { typography["titleBar"] = it.toJSON() }
        disclosure?.let { typography["disclosure"] = it.toJSON() }
        messageInput?.let { typography["messageInput"] = it.toJSON() }
        return typography
    }
}

/**
 * Styling overrides for hyperlinks within a region's text (e.g. links in the
 * disclosure or in chat bubbles).
 */
@Parcelize
data class ChatLinkStyle(
    /** The font weight (or boldness) of hyperlinks. */
    val fontWeight: Int? = null,

    /** The font style of hyperlinks: "normal" or "italic". */
    val fontStyle: String? = null,

    /**
     * Underline behavior for hyperlinks: "always", "hover", or "none". "hover"
     * (the default) underlines on hover only; on touch devices this effectively
     * means no underline at rest.
     */
    val underline: String? = null,
) : Parcelable {
    internal fun toJSON(): Map<String, Any?> {
        val json = mutableMapOf<String, Any?>()
        fontWeight?.let { json["fontWeight"] = it }
        fontStyle?.let { json["fontStyle"] = it }
        underline?.let { json["underline"] = it }
        return json
    }
}

/**
 * Typography overrides for a specific region of the chat UI (e.g. user bubbles,
 * agent bubbles, the title bar, or the disclosure text).
 */
@Parcelize
data class ChatTextStyle(
    /** The font size, in pixels. */
    val fontSize: Int? = null,

    /** The font weight, or boldness. */
    val fontWeight: Int? = null,

    /** The line height, as a unitless multiplier of the font size. */
    val lineHeight: Double? = null,

    /** The horizontal spacing between text characters, in em units. */
    val letterSpacing: Double? = null,

    /**
     * The font family, a comma-separated list of font names. Overrides the
     * global `fontFamily` for this region.
     * Note: Only built-in system fonts are supported.
     */
    val fontFamily: String? = null,

    /** The font style: "normal" or "italic". */
    val fontStyle: String? = null,

    /** Styling overrides for hyperlinks within this region's text. */
    val link: ChatLinkStyle? = null,
) : Parcelable {
    internal fun toJSON(): Map<String, Any?> {
        val json = mutableMapOf<String, Any?>()
        fontSize?.let { json["fontSize"] = it }
        fontWeight?.let { json["fontWeight"] = it }
        lineHeight?.let { json["lineHeight"] = it }
        letterSpacing?.let { json["letterSpacing"] = it }
        fontFamily?.let { json["fontFamily"] = it }
        fontStyle?.let { json["fontStyle"] = it }
        link?.let { json["link"] = it.toJSON() }
        return json
    }
}

/**
 * Color settings for chat UI. When useConfiguredStyle is true in AgentChatControllerOptions, these
 * settings are overridden by server-configured colors.
 */
@Parcelize
data class ChatStyleColors(
    /** The background color for the chat view. */
    @ColorInt val background: Int? = null,

    /** The color of the user input text and default color for assistant messages. */
    @ColorInt val text: Int? = null,

    /** The color of the border separating the user input from the chat messages. */
    @ColorInt val border: Int? = null,

    /**
     * The background color of the message input area (the region below the divider that contains
     * the text input). When null, falls back to `background`.
     */
    @ColorInt val inputBackground: Int? = null,

    /** The color of the top title bar. */
    @ColorInt val titleBar: Int? = null,

    /** The color of the text and logo in the title bar. */
    @ColorInt val titleBarText: Int? = null,

    /** The background color of the chat bubble for messages from the AI assistant. */
    @ColorInt val assistantBubble: Int? = null,

    /** The color of the text in chat bubbles for messages from the AI assistant. */
    @ColorInt val assistantBubbleText: Int? = null,

    /** The background color of the chat bubble for messages from the user. */
    @ColorInt val userBubble: Int? = null,

    /** The color of the text in chat bubbles for messages from the user. */
    @ColorInt val userBubbleText: Int? = null,

    /**
     * The color of the new-chat button. When the button appears as a flat button in the chat
     * footer, this controls the text color. When the button appears as a filled button in the
     * conversation list, this controls the background color; in that case `newChatButtonText`
     * controls the text color. When null, falls back to `userBubble`.
     */
    @ColorInt val newChatButton: Int? = null,

    /**
     * The text color of the new-chat button in the conversation list. When null, falls back to
     * `userBubbleText`.
     */
    @ColorInt val newChatButtonText: Int? = null,

    /**
     * The color of the placeholder text shown in the message input, also used for the send button
     * arrow when the input is empty. When null, falls back to `text` at reduced opacity; when set,
     * it is used at full opacity.
     */
    @ColorInt val inputPlaceholder: Int? = null,

    /**
     * The color of the file upload (attachment) button icon in the chat input. When null,
     * falls back to `userBubble`. Override this when `userBubble` does not contrast well with
     * `background` in light or dark mode.
     */
    @ColorInt val uploadButtonIcon: Int? = null,

    /** The color of the disclosure (disclaimer) text. When null, the default disclosure text color is used. */
    @ColorInt val disclosure: Int? = null,

    /** The color of links within the disclosure (disclaimer) text. */
    @ColorInt val disclosureLink: Int? = null,

    /** The color of links in chat bubbles for messages from the user. */
    @ColorInt val userBubbleLink: Int? = null,

    /** The color of links in chat bubbles for messages from the AI assistant. */
    @ColorInt val assistantBubbleLink: Int? = null,
) : Parcelable {
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun toJSON(): Map<String, String> {
        // Match the web embed's ChatStyle.colors shape.
        val colors = mapOf(
            "background" to background,
            "text" to text,
            "border" to border,
            "inputBackground" to inputBackground,
            "titleBar" to titleBar,
            "titleBarText" to titleBarText,
            "assistantBubble" to assistantBubble,
            "assistantBubbleText" to assistantBubbleText,
            "userBubble" to userBubble,
            "userBubbleText" to userBubbleText,
            "newChatButton" to newChatButton,
            "newChatButtonText" to newChatButtonText,
            "inputPlaceholder" to inputPlaceholder,
            "uploadButtonIcon" to uploadButtonIcon,
            "disclosure" to disclosure,
            "disclosureLink" to disclosureLink,
            "userBubbleLink" to userBubbleLink,
            "assistantBubbleLink" to assistantBubbleLink,
        )
        return colors.filterValues { it != null }
            .mapValues { String.format("#%06X", it.value!! and 0xFFFFFF) }
    }

}
