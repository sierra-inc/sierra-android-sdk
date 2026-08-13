// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatFragmentCompatibilityTest {
    private lateinit var activityController: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        activity = activityController.get()
    }

    @After
    fun tearDown() {
        activityController.close()
    }

    @Test
    fun fragmentHostsExtractedViewWithoutChangingControllerBehavior() {
        val controller = createController(
            AgentChatControllerOptions(
                name = "Test Agent",
                disclosurePlacement = DisclosurePlacement.BOTH,
            ),
        )
        val fragment = addFragment(controller)
        val chatView = fragment.requireView() as AgentChatView
        val webView = chatView.chatWebView()
        val shadowWebView = shadowOf(webView)

        assertSame(chatView, fragment.requireView())
        assertEquals(android.view.View.NO_ID, chatView.id)
        val loadedUrl = shadowWebView.lastLoadedUrl
        assertNotNull(loadedUrl)
        assertEquals(
            DisclosurePlacement.BOTH.value,
            Uri.parse(requireNotNull(loadedUrl)).getQueryParameter("disclosurePlacement"),
        )

        controller.sendUserMessage("hello from controller")
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("hello from controller"))
    }

    @Test
    fun fragmentRestoresTheExtractedViewsState() {
        val controller = createController()
        val original = addFragment(controller)
        val originalView = original.requireView() as AgentChatView
        originalView.setPageLoaded(true)

        val savedState = activity.supportFragmentManager.saveFragmentInstanceState(original)
        activity.supportFragmentManager.beginTransaction().remove(original).commitNow()

        val restored = controller.createFragment() as AgentChatFragment
        restored.setInitialSavedState(savedState)
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, restored)
            .commitNow()

        val restoredView = restored.requireView() as AgentChatView
        assertEquals(android.view.View.GONE, restoredView.loadingSpinner().visibility)
        assertNull(shadowOf(restoredView.chatWebView()).lastLoadedUrl)
    }

    @Test
    fun fragmentForwardsAppLifecycleAndDisconnectsDestroyedView() {
        val controller = createController()
        val fragment = addFragment(controller)
        val chatView = fragment.requireView() as AgentChatView
        val shadowWebView = shadowOf(chatView.chatWebView())
        val bridge = shadowWebView.getJavascriptInterface("AndroidSDK")

        bridge.javaClass.getMethod("onOpen").invoke(bridge)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("FOREGROUNDED"))

        activityController.pause()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("BACKGROUNDED"))

        activityController.resume()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("FOREGROUNDED"))

        controller.sendUserMessage("before destroy")
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        val lastScript = shadowWebView.lastEvaluatedJavascript
        controller.sendUserMessage("after destroy")
        assertEquals(lastScript, shadowWebView.lastEvaluatedJavascript)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun disposedViewIgnoresLateEmbedOpen() {
        val fragment = addFragment(createController())
        val chatView = fragment.requireView() as AgentChatView
        val shadowWebView = shadowOf(chatView.chatWebView())
        val bridge = shadowWebView.getJavascriptInterface("AndroidSDK")

        bridge.javaClass.getMethod("onOpen", Boolean::class.javaPrimitiveType)
            .invoke(bridge, false)
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()

        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowWebView.lastEvaluatedJavascript)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(android.view.View.VISIBLE, chatView.loadingSpinner().visibility)
    }

    private fun addFragment(controller: AgentChatController): AgentChatFragment {
        val fragment = controller.createFragment() as AgentChatFragment
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        return fragment
    }

    private fun createController(
        options: AgentChatControllerOptions = AgentChatControllerOptions(name = "Test Agent"),
    ): AgentChatController = AgentChatController(
        Agent(AgentConfig(token = "test-token", persistence = PersistenceMode.NONE)),
        options,
    )
}
