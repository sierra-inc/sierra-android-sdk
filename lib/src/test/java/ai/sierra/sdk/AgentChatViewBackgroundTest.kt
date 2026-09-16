// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A non-opaque background is painted once by the web embed, so the native views stay transparent.
 * An opaque one still paints natively, which is what avoids a flash while the embed loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatViewBackgroundTest {
    @Test
    fun opaqueBackgroundIsPaintedByTheNativeViews() {
        val background = Color.rgb(0x12, 0x34, 0x56)

        val chatView = createTestChatView(options = optionsWithBackground(background))

        assertEquals(background, chatView.backgroundColorInt())
        assertEquals(background, shadowOf(chatView.chatWebView()).backgroundColor)
    }

    @Test
    fun translucentBackgroundLeavesTheNativeViewsTransparent() {
        val chatView = createTestChatView(
            options = optionsWithBackground(Color.argb(0x80, 0x12, 0x34, 0x56)),
        )

        assertEquals(Color.TRANSPARENT, chatView.backgroundColorInt())
        assertEquals(Color.TRANSPARENT, shadowOf(chatView.chatWebView()).backgroundColor)
    }

    @Test
    fun transparentBackgroundLeavesTheNativeViewsTransparent() {
        val chatView = createTestChatView(options = optionsWithBackground(Color.TRANSPARENT))

        assertEquals(Color.TRANSPARENT, chatView.backgroundColorInt())
        assertEquals(Color.TRANSPARENT, shadowOf(chatView.chatWebView()).backgroundColor)
    }

    @Test
    fun serverConfiguredStyleLeavesTheNativeViewsTransparent() {
        val chatView = createTestChatView(
            options = optionsWithBackground(Color.WHITE, useConfiguredStyle = true),
        )

        assertEquals(Color.TRANSPARENT, chatView.backgroundColorInt())
        assertEquals(Color.TRANSPARENT, shadowOf(chatView.chatWebView()).backgroundColor)
    }

    private fun optionsWithBackground(
        background: Int,
        useConfiguredStyle: Boolean = false,
    ) = AgentChatControllerOptions(
        name = "Test Agent",
        useConfiguredStyle = useConfiguredStyle,
        chatStyle = ChatStyle(colors = ChatStyleColors(background = background)),
    )

    private fun View.backgroundColorInt() = (background as ColorDrawable).color
}
