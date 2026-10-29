// Copyright Sierra

package ai.sierra.sdk

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
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
        val fileChooserParams = object : WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = MODE_OPEN

            override fun getAcceptTypes(): Array<String> = arrayOf("image/jpeg,image/png")

            override fun isCaptureEnabled(): Boolean = false

            override fun getTitle(): CharSequence? = null

            override fun getFilenameHint(): String? = null

            override fun createIntent(): Intent = error("AgentChatView builds the chooser intent")
        }

        assertTrue(
            shadowOf(webView).webChromeClient.onShowFileChooser(
                webView,
                callback,
                fileChooserParams,
            ),
        )
        val expectedPhotoPickerIntent = ActivityResultContracts.PickVisualMedia().createIntent(
            chatView.context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
        assertEquals(expectedPhotoPickerIntent.action, launchedIntent?.action)

        val selected = arrayOf(Uri.parse("content://media/external/images/media/42"))
        chatView.onFileChooserResult(selected)
        assertArrayEquals(selected, callbackResult)
    }

    @Test
    fun fileChooserIntentUsesAcceptedMimeTypes() {
        val context = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val expectedPhotoPickerAction = ActivityResultContracts.PickVisualMedia().createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        ).action

        val imagesOnly = createFileChooserIntent(
            context,
            arrayOf(" image/JPEG,IMAGE/png ", "", ".jpg"),
            allowMultiple = false,
        )
        assertEquals(expectedPhotoPickerAction, imagesOnly.action)
        assertEquals("image/*", imagesOnly.type)

        val mixed = createFileChooserIntent(
            context,
            arrayOf(" image/JPEG, application/PDF ", ".docx"),
            allowMultiple = true,
        )
        assertEquals(Intent.ACTION_CHOOSER, mixed.action)
        val getContent = IntentCompat.getParcelableExtra(
            mixed,
            Intent.EXTRA_INTENT,
            Intent::class.java,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, getContent?.action)
        assertTrue(getContent?.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertEquals("*/*", getContent?.type)
        assertArrayEquals(
            arrayOf("image/jpeg", "application/pdf"),
            getContent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertTrue(getContent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
        val initialIntents = IntentCompat.getParcelableArrayExtra(
            mixed,
            Intent.EXTRA_INITIAL_INTENTS,
            Intent::class.java,
        )
        assertEquals(1, initialIntents?.size)
        assertEquals(expectedPhotoPickerAction, (initialIntents?.single() as? Intent)?.action)

        val documentsOnly = createFileChooserIntent(
            context,
            arrayOf("application/pdf"),
            allowMultiple = false,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, documentsOnly.action)
        assertArrayEquals(
            arrayOf("application/pdf"),
            documentsOnly.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )

        val empty = createFileChooserIntent(
            context,
            arrayOf("", "  ", ".docx"),
            allowMultiple = false,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, empty.action)
        assertEquals("*/*", empty.type)
        assertNull(empty.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
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
