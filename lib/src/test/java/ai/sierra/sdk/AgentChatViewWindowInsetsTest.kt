// Copyright Sierra

package ai.sierra.sdk

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentChatViewWindowInsetsTest {
    @Test
    fun imeInsetsAreConsumedWithoutDoublePadding() {
        val chatView = createTestChatView()
        chatView.setPadding(1, 2, 3, 4)
        val imeType = WindowInsetsCompat.Type.ime()

        val remainingInsets = ViewCompat.dispatchApplyWindowInsets(
            chatView,
            WindowInsetsCompat.Builder()
                .setInsets(imeType, Insets.of(0, 0, 0, 480))
                .setVisible(imeType, true)
                .build(),
        )

        assertEquals(1, chatView.paddingLeft)
        assertEquals(2, chatView.paddingTop)
        assertEquals(3, chatView.paddingRight)
        assertEquals(4, chatView.paddingBottom)
        assertEquals(0, remainingInsets.getInsets(imeType).bottom)
        assertFalse(remainingInsets.isVisible(imeType))
    }

    @Test
    fun imeOverlapUsesVisibleWindowGeometry() {
        assertEquals(819, calculateImeOverlap(2339, 1520, imeVisible = true))
        assertEquals(0, calculateImeOverlap(1520, 1520, imeVisible = true))
        assertEquals(0, calculateImeOverlap(1400, 1520, imeVisible = true))
        assertEquals(0, calculateImeOverlap(2339, 1520, imeVisible = false))
    }

    @Test
    fun hostPaddingSetWhileImeVisibleIsPreservedWhenImeCloses() {
        val chatView = createTestChatView()
        chatView.setPadding(1, 2, 3, 0)

        chatView.applyImeBottomPadding(819)
        assertEquals(819, chatView.paddingBottom)

        chatView.setPadding(4, 5, 6, 48)
        assertEquals(867, chatView.paddingBottom)

        chatView.applyImeBottomPadding(0)
        assertEquals(4, chatView.paddingLeft)
        assertEquals(5, chatView.paddingTop)
        assertEquals(6, chatView.paddingRight)
        assertEquals(48, chatView.paddingBottom)
    }

    @Test
    fun hostRelativePaddingSetWhileImeVisibleIsPreservedWhenImeCloses() {
        val chatView = createTestChatView()
        chatView.applyImeBottomPadding(819)

        chatView.setPaddingRelative(4, 5, 6, 48)
        assertEquals(867, chatView.paddingBottom)

        chatView.applyImeBottomPadding(0)
        assertEquals(48, chatView.paddingBottom)
    }
}
