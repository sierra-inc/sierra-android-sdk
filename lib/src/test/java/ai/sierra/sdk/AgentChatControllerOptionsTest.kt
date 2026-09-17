// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri
import android.os.Parcel
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
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
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentChatControllerOptionsTest {
    @Test
    fun chatButtonOptionsAreForwardedToUrlAndOmittedByDefault() {
        val defaultUrl = loadedUrl(AgentChatControllerOptions(name = "Test"))
        assertNull(defaultUrl.getQueryParameter("footerEndConversationButtonStyle"))
        assertNull(defaultUrl.getQueryParameter("messageInputPresetAction"))
        assertNull(defaultUrl.getQueryParameter("endConversationConfirmationStyle"))
        val emptyStyleUrl = loadedUrl(
            AgentChatControllerOptions(
                name = "Test",
                endConversationConfirmationStyle = EndConversationConfirmationStyle(
                    confirmButton = ChatButtonStyle(),
                ),
            ),
        )
        assertNull(emptyStyleUrl.getQueryParameter("endConversationConfirmationStyle"))
        assertNull(defaultUrl.getQueryParameter("conversationEndedStyle"))
        assertNull(ChatConversationEndedStyle().toJSONString())

        val style = ChatButtonStyle(alignment = ChatButtonStyle.Alignment.END, borderRadius = "22px")
        val url = loadedUrl(
            AgentChatControllerOptions(
                name = "Test",
                footerEndConversationButtonStyle = style,
                endConversationConfirmationStyle = EndConversationConfirmationStyle(
                    showFooterDivider = false,
                    confirmButton = ChatButtonStyle(height = "48px"),
                    cancelButton = ChatButtonStyle(height = "40px"),
                ),
                conversationEndedStyle = ChatConversationEndedStyle(
                    messageAlignment = ChatConversationEndedStyle.MessageAlignment.CENTER,
                    showComposerContainer = false,
                    actionSpacing = 0,
                    newChatButtonStyle = style,
                ),
                messageInputPresetAction = MessageInputPresetAction(
                    label = "Track my order",
                    clientEvent = MessageInputPresetAction.ClientEvent(
                        MessageInputPresetAction.ClientEvent.Message("Where is my order?"),
                    ),
                    showAfterAgentMessageCount = 1,
                    style = style,
                ),
            ),
        )

        val footerStyle = JSONObject(url.getQueryParameter("footerEndConversationButtonStyle")!!)
        assertEquals("end", footerStyle.getString("alignment"))
        assertEquals("22px", footerStyle.getString("borderRadius"))
        val action = JSONObject(url.getQueryParameter("messageInputPresetAction")!!)
        assertEquals("Track my order", action.getString("label"))
        assertEquals(1, action.getInt("showAfterAgentMessageCount"))
        assertEquals("message", action.getJSONObject("clientEvent").getString("type"))
        assertEquals(
            "Where is my order?",
            action.getJSONObject("clientEvent").getJSONObject("message").getString("content"),
        )
        assertEquals("end", action.getJSONObject("style").getString("alignment"))
        assertEquals("22px", action.getJSONObject("style").getString("borderRadius"))
        val confirmation = JSONObject(url.getQueryParameter("endConversationConfirmationStyle")!!)
        assertFalse(confirmation.getBoolean("showFooterDivider"))
        assertEquals("48px", confirmation.getJSONObject("confirmButton").getString("height"))
        assertEquals("40px", confirmation.getJSONObject("cancelButton").getString("height"))
        val ended = JSONObject(url.getQueryParameter("conversationEndedStyle")!!)
        assertEquals("center", ended.getString("messageAlignment"))
        assertFalse(ended.getBoolean("showComposerContainer"))
        assertEquals(0, ended.getInt("actionSpacing"))
        assertEquals("end", ended.getJSONObject("newChatButtonStyle").getString("alignment"))
        assertEquals("22px", ended.getJSONObject("newChatButtonStyle").getString("borderRadius"))
    }

    @Test
    fun disclosureBehaviorIsForwardedToUrl() {
        val defaultUrl = loadedUrl(AgentChatControllerOptions(name = "Test"))
        assertNull(defaultUrl.getQueryParameter("disclosurePosition"))
        assertFalse(defaultUrl.getBooleanQueryParameter("hideDisclosureDuringLiveChat", false))

        val url = loadedUrl(
            AgentChatControllerOptions(
                name = "Test",
                disclosurePosition = DisclosurePosition.PINNED_BELOW_COMPOSER,
                hideDisclosureDuringLiveChat = true,
            ),
        )

        assertEquals("pinnedBelowComposer", url.getQueryParameter("disclosurePosition"))
        assertTrue(url.getBooleanQueryParameter("hideDisclosureDuringLiveChat", false))
    }

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
            endConversationConfirmationStyle = EndConversationConfirmationStyle(
                showFooterDivider = false,
                confirmButton = ChatButtonStyle(height = "48px", width = "100%"),
                cancelButton = ChatButtonStyle(height = "40px", width = "160px"),
            ),
            disclosurePosition = DisclosurePosition.PINNED_BELOW_COMPOSER,
            hideDisclosureDuringLiveChat = true,
            conversationEndedStyle = ChatConversationEndedStyle(
                messageAlignment = ChatConversationEndedStyle.MessageAlignment.CENTER,
                showComposerContainer = false,
                actionSpacing = 0,
                newChatButtonStyle = ChatButtonStyle(width = "100%", height = "48px"),
            ),
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

    @Test
    fun conversationIDIsForwardedWithUserIdentityToken() {
        val url = loadedUrl(
            options = AgentChatControllerOptions(
                name = "Test",
                userIdentityToken = "user-identity-token",
            ),
            conversationID = "external-123",
        )

        assertEquals("external-123", url.getQueryParameter("conversationID"))
    }

    @Test
    fun conversationIDRequiresUserIdentityToken() {
        val url = loadedUrl(
            options = AgentChatControllerOptions(name = "Test"),
            conversationID = "external-123",
        )

        assertNull(url.getQueryParameter("conversationID"))
    }

    @Test
    fun conversationStateTakesPrecedenceOverConversationID() {
        val url = loadedUrl(
            options = AgentChatControllerOptions(
                name = "Test",
                userIdentityToken = "user-identity-token",
            ),
            conversationState = "opaque-state",
            conversationID = "external-123",
        )

        assertEquals("opaque-state", url.getQueryParameter("state"))
        assertNull(url.getQueryParameter("conversationID"))
    }

    private fun loadedUrl(
        options: AgentChatControllerOptions,
        conversationState: String? = null,
        conversationID: String? = null,
    ): Uri {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = AgentChatController(
            agent = Agent(AgentConfig(token = "test-token")),
            options = options,
            conversationState = conversationState,
            conversationID = conversationID,
        ).createFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        val webView = (fragment.requireView() as ViewGroup).getChildAt(0) as WebView

        return Uri.parse(shadowOf(webView).lastLoadedUrl)
    }
}
