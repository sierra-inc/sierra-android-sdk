// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri
import android.os.Parcel
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentChatControllerOptionsTest {
    @Test
    fun confirmEndConversationModeDefaultsToAlwaysAndIsOmittedFromUrl() {
        val options = AgentChatControllerOptions(name = "Test")

        assertEquals(EndConversationConfirmationMode.ALWAYS, options.confirmEndConversationMode)
        assertNull(loadedUrl(options).getQueryParameter("confirmEndConversationMode"))
    }

    @Test
    fun liveChatConfirmEndConversationModeIsForwardedToUrlWithoutEnablingConfirmation() {
        val options = AgentChatControllerOptions(
            name = "Test",
            confirmEndConversationMode = EndConversationConfirmationMode.LIVE_CHAT,
        )
        val url = loadedUrl(options)

        assertEquals("liveChat", url.getQueryParameter("confirmEndConversationMode"))
        assertFalse(url.getBooleanQueryParameter("confirmEndConversation", false))
    }

    @Test
    fun confirmEndConversationModeSurvivesParcelableRoundTrip() {
        val options = AgentChatControllerOptions(
            name = "Test",
            greetingMessage = "Welcome",
            disclosure = "Test disclosure",
            canEndConversation = true,
            confirmEndConversation = true,
            footerEndConversationButton = true,
            initialUserMessage = "Hello",
            confirmEndConversationMode = EndConversationConfirmationMode.LIVE_CHAT,
        )
        val parcel = Parcel.obtain()

        try {
            parcel.writeParcelable(options, 0)
            parcel.setDataPosition(0)

            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<AgentChatControllerOptions>(
                AgentChatControllerOptions::class.java.classLoader
            )
            assertEquals(options, restored)
        } finally {
            parcel.recycle()
        }
    }

    private fun loadedUrl(options: AgentChatControllerOptions): Uri {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = AgentChatController(
            agent = Agent(AgentConfig(token = "test-token")),
            options = options,
        ).createFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        val webView = (fragment.requireView() as ViewGroup).getChildAt(0) as WebView

        return Uri.parse(shadowOf(webView).lastLoadedUrl)
    }
}
