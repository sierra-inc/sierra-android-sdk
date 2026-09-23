// Copyright Sierra

package ai.sierra.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChatComposerStyleTest {
    @Test
    fun composerColorsPreserveExistingPositionalArguments() {
        val colors = ChatStyleColors(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        )

        assertEquals(5, colors.titleBar)
        assertEquals(6, colors.titleBarText)
        assertEquals(19, colors.inputBorder)
        assertEquals(20, colors.inputText)
        assertEquals(21, colors.humanAgentBubble)
        assertEquals(22, colors.humanAgentBubbleText)
        assertEquals(23, colors.humanAgentBubbleLink)
        assertNull(colors.assistantBubbleBorder)
        assertNull(colors.userBubbleBorder)
        assertNull(colors.humanAgentBubbleBorder)
    }

    @Test
    fun emptyStyleIsNotSerialized() {
        // The query parameter is omitted entirely, so the embed keeps its defaults.
        assertNull(ChatComposerStyle().toJSONString())
    }

    @Test
    fun insetsUseLogicalStartAndEndKeys() {
        val style = ChatComposerStyle(
            outerInsets = ChatComposerInsets(top = 0, start = 16, bottom = 16, end = 16),
        )

        assertEquals(
            mapOf("top" to 0, "start" to 16, "bottom" to 16, "end" to 16),
            style.toJSON()["outerInsets"],
        )
    }

    @Test
    fun omittedFieldsAreNotSerialized() {
        val style = ChatComposerStyle(minimumHeight = 50)

        assertEquals(mapOf("minimumHeight" to 50), style.toJSON())
    }

    // Distinct values per field, so a swapped key fails.
    @Test
    fun everySuppliedFieldIsSerialized() {
        val style = ChatComposerStyle(
            outerInsets = ChatComposerInsets(top = 0, start = 16, bottom = 16, end = 16),
            contentInsets = ChatComposerInsets(top = 8, start = 12, bottom = 8, end = 8),
            minimumHeight = 50,
            maximumLines = 3,
            cornerRadius = 25,
            borderWidth = 1,
            actionButtonSize = 28,
        )

        // Nested insets have to survive as JSON objects, not stringified maps.
        val json = JSONObject(style.toJSONString()!!)
        assertEquals(16, json.getJSONObject("outerInsets").getInt("start"))
        assertEquals(12, json.getJSONObject("contentInsets").getInt("start"))
        assertEquals(50, json.getInt("minimumHeight"))
        assertEquals(3, json.getInt("maximumLines"))
        assertEquals(25, json.getInt("cornerRadius"))
        assertEquals(1, json.getInt("borderWidth"))
        assertEquals(28, json.getInt("actionButtonSize"))
    }
}
