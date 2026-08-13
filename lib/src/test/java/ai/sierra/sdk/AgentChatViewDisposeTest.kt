// Copyright Sierra

package ai.sierra.sdk

import android.app.Activity
import android.os.Looper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AgentChatController.createView] initializes through [AgentChatView.initializeWhenAttached], which
 * posts the actual initialization so hierarchy-state restoration gets to run first. A host that
 * discards the view inside that same message-queue turn -- Compose releasing a node on a host-key
 * change, a Fragment tearing down its view -- must not end up loading the chat into a view nothing is
 * connected to any more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatViewDisposeTest {
    @Test
    fun disposeBeforePostedInitializationRunsLoadsNothing() {
        val view = attachedView()

        view.initializeWhenAttached()
        view.dispose()
        drainMainLooper()

        // The view is still attached, so the posted runnable's attachment check does not save us.
        assertTrue(view.isAttachedToWindow)
        assertNull(
            "initialize() ran after dispose() and loaded the chat",
            shadowOf(view.chatWebView()).lastLoadedUrl,
        )
    }

    /** Without this control the test above would also pass if initialization never ran at all. */
    @Test
    fun postedInitializationLoadsWhenTheViewIsNotDisposed() {
        val view = attachedView()

        view.initializeWhenAttached()
        drainMainLooper()

        assertNotNull(
            "posted initialization never loaded the chat",
            shadowOf(view.chatWebView()).lastLoadedUrl,
        )
    }

    @Test
    fun initializeAfterDisposeLoadsNothing() {
        val view = attachedView()

        view.dispose()
        view.initialize()

        assertNull(
            "initialize() loaded the chat into a disposed view",
            shadowOf(view.chatWebView()).lastLoadedUrl,
        )
    }

    /** Attachment matters: initialization is only posted once the view is attached to a window. */
    private fun attachedView(): AgentChatView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return createTestChatView().also { activity.setContentView(it) }
    }

    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}
