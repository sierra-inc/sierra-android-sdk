// Copyright Sierra

package ai.sierra.sdk

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    fun topInsetOverlapAccountsForViewPositionAndHostPadding() {
        assertEquals(63, calculateTopInsetOverlap(viewTop = 0, hostTopPadding = 0, topInset = 63))
        assertEquals(0, calculateTopInsetOverlap(viewTop = 63, hostTopPadding = 0, topInset = 63))
        assertEquals(0, calculateTopInsetOverlap(viewTop = 0, hostTopPadding = 63, topInset = 63))
        assertEquals(33, calculateTopInsetOverlap(viewTop = 20, hostTopPadding = 10, topInset = 63))
        assertEquals(0, calculateTopInsetOverlap(viewTop = 0, hostTopPadding = 0, topInset = 0))
    }

    @Test
    fun systemBarTopInsetIsConsumedOnlyForBuiltInTitleBar() {
        val systemBarsType = WindowInsetsCompat.Type.systemBars()
        val windowInsets = WindowInsetsCompat.Builder()
            .setInsets(systemBarsType, Insets.of(1, 63, 2, 24))
            .setVisible(systemBarsType, true)
            .build()

        val builtInTitleBarInsets = ViewCompat.dispatchApplyWindowInsets(
            createTestChatView(),
            windowInsets,
        )
        val hiddenTitleBarInsets = ViewCompat.dispatchApplyWindowInsets(
            createTestChatView(
                options = AgentChatControllerOptions(name = "Test Agent", hideTitleBar = true),
            ),
            windowInsets,
        )

        assertEquals(Insets.of(1, 0, 2, 24), builtInTitleBarInsets.getInsets(systemBarsType))
        assertEquals(Insets.of(1, 63, 2, 24), hiddenTitleBarInsets.getInsets(systemBarsType))
    }

    @Test
    fun displayCutoutTopInsetIsConsumedWhenStatusBarIsHidden() {
        val statusBarsType = WindowInsetsCompat.Type.statusBars()
        val displayCutoutType = WindowInsetsCompat.Type.displayCutout()
        val windowInsets = WindowInsetsCompat.Builder()
            .setInsets(statusBarsType, Insets.NONE)
            .setVisible(statusBarsType, false)
            .setInsets(displayCutoutType, Insets.of(3, 96, 4, 5))
            .build()

        val builtInTitleBarInsets = ViewCompat.dispatchApplyWindowInsets(
            createTestChatView(),
            windowInsets,
        )
        val hiddenTitleBarInsets = ViewCompat.dispatchApplyWindowInsets(
            createTestChatView(
                options = AgentChatControllerOptions(name = "Test Agent", hideTitleBar = true),
            ),
            windowInsets,
        )

        assertEquals(Insets.NONE, builtInTitleBarInsets.getInsets(statusBarsType))
        assertEquals(Insets.of(3, 0, 4, 5), builtInTitleBarInsets.getInsets(displayCutoutType))
        assertEquals(Insets.NONE, hiddenTitleBarInsets.getInsets(statusBarsType))
        assertEquals(Insets.of(3, 96, 4, 5), hiddenTitleBarInsets.getInsets(displayCutoutType))
    }

    @Test
    fun hostTopPaddingIsPreservedAcrossInsetChanges() {
        val chatView = createTestChatView()
        chatView.setPadding(1, 10, 3, 4)

        chatView.applyTopInsetPadding(53)
        assertEquals(63, chatView.paddingTop)

        chatView.setPadding(5, 20, 7, 8)
        assertEquals(73, chatView.paddingTop)

        chatView.applyTopInsetPadding(43)
        assertEquals(63, chatView.paddingTop)

        chatView.applyTopInsetPadding(0)
        assertEquals(5, chatView.paddingLeft)
        assertEquals(20, chatView.paddingTop)
        assertEquals(7, chatView.paddingRight)
        assertEquals(8, chatView.paddingBottom)
    }

    @Test
    fun partialPaddingUpdateDoesNotPersistAppliedInsets() {
        val chatView = createTestChatView()
        chatView.setPadding(1, 10, 3, 4)
        chatView.applyTopInsetPadding(53)
        chatView.applyImeBottomPadding(819)

        chatView.updatePadding(left = 5)
        assertEquals(63, chatView.paddingTop)
        assertEquals(823, chatView.paddingBottom)

        chatView.applyTopInsetPadding(0)
        chatView.applyImeBottomPadding(0)
        assertEquals(5, chatView.paddingLeft)
        assertEquals(10, chatView.paddingTop)
        assertEquals(4, chatView.paddingBottom)
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
    fun hostRelativePaddingSetWhileInsetsAreVisibleIsPreservedWhenInsetsClear() {
        val chatView = createTestChatView()
        chatView.applyTopInsetPadding(63)
        chatView.applyImeBottomPadding(819)

        chatView.setPaddingRelative(4, 20, 6, 48)
        assertEquals(83, chatView.paddingTop)
        assertEquals(867, chatView.paddingBottom)

        chatView.applyTopInsetPadding(0)
        chatView.applyImeBottomPadding(0)
        assertEquals(20, chatView.paddingTop)
        assertEquals(48, chatView.paddingBottom)
    }
}
