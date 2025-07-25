// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.annotation.ColorInt

@Parcelize
data class ChatStyle (
    val colors: ChatStyleColors = ChatStyleColors(),
    val typography: ChatStyleTypography? = null
): Parcelable {
    internal fun toJSON(): Map<String, Any> {
        // Match the ChatStyle type from ui/chat/chat.tsx.
        val json = mutableMapOf<String, Any>(
            "colors" to colors.toJSON()
        )
        // Serialize as "type" to match the ChatStyle type from ui/chat/chat.tsx
        typography?.let {
            json["type"] = it.toJSON()
        }
        return json
    }
}

@Parcelize
data class ChatStyleTypography(
    /**
     * The font family, a comma-separated list of font names.
     * Note: Only built-in system fonts are supported. Custom fonts loaded by the app are not available.
     */
    val fontFamily: String? = null,

    /** The font size, in pixels. */
    val fontSize: Int? = null
) : Parcelable {
    internal fun toJSON(): Map<String, Any?> {
        val typography = mutableMapOf<String, Any?>()
        fontFamily?.let { typography["fontFamily"] = it }
        fontSize?.let {
            typography["fontSize"] = it
            // Set all responsive font sizes
            typography["fontSize900"] = it
            typography["fontSize750"] = it
            typography["fontSize500"] = it
        }
        return typography
    }
}

@Parcelize
data class ChatStyleColors(
    /** The background color for the chat view. */
    @ColorInt val background: Int? = null,

    /** The color of the user input text and default color for assistant messages. */
    @ColorInt val text: Int? = null,

    /** The color of the border separating the user input from the chat messages. */
    @ColorInt val border: Int? = null,

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
) : Parcelable {
    internal fun toJSON(): Map<String, String> {
        // Match the ChatStyle.colors type from ui/chat/chat.tsx.
        val colors = mapOf(
            "background" to background,
            "text" to text,
            "border" to border,
            "titleBar" to titleBar,
            "titleBarText" to titleBarText,
            "assistantBubble" to assistantBubble,
            "assistantBubbleText" to assistantBubbleText,
            "userBubble" to userBubble,
            "userBubbleText" to userBubbleText,
        )
        return colors.filterValues { it != null }
            .mapValues { String.format("#%06X", it.value!! and 0xFFFFFF) }
    }

}
