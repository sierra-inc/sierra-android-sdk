// Copyright Sierra

package ai.sierra.sdk

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ValueCallback
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatViewLifecycleTest {
    @Test
    fun controllerViewUsesRequestedStableId() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()

        val chatView = createController().createView(
            context = activityController.get(),
            viewId = android.R.id.button1,
        )

        assertEquals(android.R.id.button1, chatView.id)
        activityController.close()
    }

    @Test
    fun controllerViewInitializesOnAttachAndFollowsItsHostLifecycle() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = activityController.get()
        val controller = createController()
        val chatView = controller.createView(activity)
        val shadowWebView = shadowOf(chatView.chatWebView())

        assertNull(shadowWebView.lastLoadedUrl)
        activity.setContentView(chatView)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requireNotNull(shadowWebView.lastLoadedUrl).contains("test-token"))

        val bridge = shadowWebView.getJavascriptInterface("AndroidSDK")
        bridge.javaClass.getMethod("onOpen").invoke(bridge)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("FOREGROUNDED"))

        activityController.pause()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("BACKGROUNDED"))

        activityController.resume()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("FOREGROUNDED"))

        activityController.destroy()
        val lastScript = shadowWebView.lastEvaluatedJavascript
        controller.sendUserMessage("after lifecycle destroy")
        assertEquals(lastScript, shadowWebView.lastEvaluatedJavascript)
    }

    @Test
    fun detachedViewFollowsHostLifecycleUntilDisposed() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = activityController.get()
        val controller = createController()
        val chatView = controller.createView(activity)
        activity.setContentView(chatView)
        shadowOf(Looper.getMainLooper()).idle()

        val shadowWebView = shadowOf(chatView.chatWebView())
        val bridge = shadowWebView.getJavascriptInterface("AndroidSDK")
        bridge.javaClass.getMethod("onOpen").invoke(bridge)
        shadowOf(Looper.getMainLooper()).idle()
        (chatView.parent as ViewGroup).removeView(chatView)

        activityController.pause()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("BACKGROUNDED"))

        activityController.destroy()
        val lastScript = shadowWebView.lastEvaluatedJavascript
        controller.sendUserMessage("after detached host destroy")
        assertEquals(lastScript, shadowWebView.lastEvaluatedJavascript)
    }

    @Test
    fun disposingBeforeAttachmentPreventsDeferredInitialization() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val controller = createController()
        val chatView = controller.createView(activityController.get())
        val shadowWebView = shadowOf(chatView.chatWebView())

        chatView.dispose()
        activityController.get().setContentView(chatView)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowWebView.lastLoadedUrl)
        controller.sendUserMessage("after dispose")
        assertNull(shadowWebView.lastEvaluatedJavascript)
        activityController.close()
    }

    @Test
    fun fileChooserResultIsReturnedToTheWebView() {
        var launchedIntent: Intent? = null
        val chatView = createTestChatView(fileChooserLauncher = { launchedIntent = it })
        val webView = chatView.chatWebView()
        var callbackResult: Array<Uri>? = null
        val callback = ValueCallback<Array<Uri>> { callbackResult = it }

        assertTrue(
            shadowOf(webView).webChromeClient.onShowFileChooser(webView, callback, null),
        )
        assertEquals(Intent.ACTION_GET_CONTENT, launchedIntent?.action)

        val selected = arrayOf(Uri.parse("content://test/attachment"))
        chatView.onFileChooserResult(selected)
        assertArrayEquals(selected, callbackResult)
    }

    @Test
    fun missingFileChooserLauncherCancelsTheWebCallback() {
        val chatView = createTestChatView()
        val webView = chatView.chatWebView()
        var callbackCalled = false
        var callbackResult: Array<Uri>? = emptyArray()
        val callback = ValueCallback<Array<Uri>> {
            callbackCalled = true
            callbackResult = it
        }

        assertFalse(
            shadowOf(webView).webChromeClient.onShowFileChooser(webView, callback, null),
        )
        assertTrue(callbackCalled)
        assertNull(callbackResult)
    }

    private fun createController(): AgentChatController = AgentChatController(
        Agent(AgentConfig(token = "test-token", persistence = PersistenceMode.NONE)),
        AgentChatControllerOptions(name = "Test Agent"),
    )
}
