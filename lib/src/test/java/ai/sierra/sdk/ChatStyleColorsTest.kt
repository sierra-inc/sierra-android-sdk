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
}
