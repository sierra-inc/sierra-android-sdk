// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChatStyleColorsTest {
    @Test
    fun opaqueColorUsesSixDigitHex() {
        val colors = ChatStyleColors(background = Color.argb(255, 0x12, 0x34, 0x56))

        assertEquals("#123456", colors.toJSON()["background"])
    }

    @Test
    fun partialAlphaUsesCssOrderEightDigitHex() {
        val colors = ChatStyleColors(userBubble = Color.argb(0x80, 0x12, 0x34, 0x56))

        assertEquals("#12345680", colors.toJSON()["userBubble"])
    }

    @Test
    fun zeroAlphaIsSerializedAsTransparent() {
        val colors = ChatStyleColors(assistantBubble = Color.argb(0, 0x12, 0x34, 0x56))

        assertEquals("#12345600", colors.toJSON()["assistantBubble"])
    }

    @Test
    fun nullColorsAreOmitted() {
        val colors = ChatStyleColors(text = Color.BLACK)

        assertEquals(mapOf("text" to "#000000"), colors.toJSON())
    }

    @Test
    fun humanAgentColorsUseEmbedKeys() {
        val colors = ChatStyleColors(
            humanAgentBubble = Color.argb(0x80, 0x12, 0x34, 0x56),
            humanAgentBubbleText = Color.BLACK,
            humanAgentBubbleLink = Color.BLUE,
        )
        assertEquals(mapOf(
            "humanAgentBubble" to "#12345680",
            "humanAgentBubbleText" to "#000000",
            "humanAgentBubbleLink" to "#0000FF",
        ), colors.toJSON())
    }

    @Test
    fun bubbleBorderColorsUseEmbedKeys() {
        val colors = ChatStyleColors(
            assistantBubbleBorder = Color.argb(0x80, 0xFF, 0, 0),
            userBubbleBorder = Color.argb(0x40, 0, 0xFF, 0),
            humanAgentBubbleBorder = Color.argb(0, 0, 0, 0xFF),
        )

        assertEquals("#FF000080", colors.toJSON()["assistantBubbleBorder"])
        assertEquals("#00FF0040", colors.toJSON()["userBubbleBorder"])
        assertEquals("#0000FF00", colors.toJSON()["humanAgentBubbleBorder"])
    }
}
