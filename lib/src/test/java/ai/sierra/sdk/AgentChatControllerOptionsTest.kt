// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWebView
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
                    showDisclosure = false,
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
        assertFalse(ended.getBoolean("showDisclosure"))
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
    fun bubbleTailOptionIsForwardedInBrandOnlyWhenConfigured() {
        val defaultBrand = JSONObject(
            loadedUrl(AgentChatControllerOptions(name = "Test")).getQueryParameter("brand")!!,
        )
        assertFalse(defaultBrand.has("hideBubbleTails"))

        val brand = JSONObject(
            loadedUrl(
                AgentChatControllerOptions(
                    name = "Test",
                    hideBubbleTails = false,
                ),
            ).getQueryParameter("brand")!!,
        )
        assertFalse(brand.getBoolean("hideBubbleTails"))
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
    fun initialUserMessageFrequencyDefaultsToEveryConversationAndForwardsOptIn() {
        val defaultOptions = AgentChatControllerOptions(name = "Test")
        assertEquals(
            InitialUserMessageFrequency.EVERY_CONVERSATION,
            defaultOptions.initialUserMessageFrequency,
        )
        assertNull(loadedUrl(defaultOptions).getQueryParameter("initialUserMessageFrequency"))

        val url = loadedUrl(
            AgentChatControllerOptions(
                name = "Test",
                initialUserMessageFrequency = InitialUserMessageFrequency.ONCE_PER_CHAT_INSTANCE,
            ),
        )
        assertEquals(
            "oncePerChatInstance",
            url.getQueryParameter("initialUserMessageFrequency"),
        )
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
            initialUserMessageFrequency = InitialUserMessageFrequency.ONCE_PER_CHAT_INSTANCE,
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
                showDisclosure = false,
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
    fun initialConversationUsesBridgeAndHeaderWithCleanUrl() {
        withLoadedChat(
            options = AgentChatControllerOptions(name = "Test", userIdentityToken = "user-identity-token"),
            conversationID = "external-123",
        ) { webView ->
            assertCleanUrl(webView)
            val payload = initialConversation(webView)
            assertEquals("user-identity-token", payload.getString("userIdentityToken"))
            assertEquals("conversationID", payload.getJSONObject("target").getString("kind"))
            assertEquals("external-123", payload.getJSONObject("target").getString("conversationID"))
            assertEquals("user-identity-token", webView.lastAdditionalHttpHeaders["X-Sierra-User-Identity-Token"])
        }
    }

    @Test
    fun conversationIDRequiresUserIdentityToken() {
        for (identity in listOf(null, "")) {
            withLoadedChat(
                options = AgentChatControllerOptions(name = "Test", userIdentityToken = identity),
                conversationID = "external-123",
            ) { webView ->
                assertCleanUrl(webView)
                val payload = initialConversation(webView)
                assertFalse(payload.has("userIdentityToken"))
                assertEquals("none", payload.getJSONObject("target").getString("kind"))
                assertFalse(webView.lastAdditionalHttpHeaders.containsKey("X-Sierra-User-Identity-Token"))
            }
        }
    }

    @Test
    fun conversationStateTakesPrecedenceOverConversationID() {
        withLoadedChat(
            options = AgentChatControllerOptions(name = "Test"),
            conversationState = "opaque-state-with-\\-and-\"-and-\u2028",
            conversationID = "external-123",
        ) { webView ->
            assertCleanUrl(webView)
            val target = initialConversation(webView).getJSONObject("target")
            assertEquals("state", target.getString("kind"))
            assertEquals("opaque-state-with-\\-and-\"-and-\u2028", target.getString("state"))
            assertFalse(target.has("conversationID"))
        }
    }

    @Test
    fun refreshedIdentityIsUsedAfterStateRestoration() {
        lateinit var replyToIdentityRefresh: (SecretExpiryResult) -> Unit
        val listener = object : ConversationEventListener {
            override fun onUserIdentityTokenExpiry(
                replyHandler: (SecretExpiryResult) -> Unit,
            ) {
                replyToIdentityRefresh = replyHandler
            }
        }
        val options = AgentChatControllerOptions(
            name = "Test",
            userIdentityToken = "expired-identity-token",
        )
        val original = createTestChatView(
            conversationID = "external-123",
            options = options,
            listener = listener,
        )
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onUserIdentityTokenExpiry", String::class.java)
                .invoke(bridge, "identity-refresh")
            replyToIdentityRefresh(SecretExpiryResult.Success("refreshed-identity-token"))
            shadowOf(Looper.getMainLooper()).idle()
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(
            conversationID = "external-123",
            options = options,
        )
        try {
            restored.initialize(savedState)
            val webView = shadowOf(restored.chatWebView())
            assertEquals(
                "refreshed-identity-token",
                webView.lastAdditionalHttpHeaders["X-Sierra-User-Identity-Token"],
            )
            assertEquals(
                "refreshed-identity-token",
                initialConversation(webView).getString("userIdentityToken"),
            )
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun conversationListIsRestoredInsteadOfTheAbandonedConversation() {
        val options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token")
        val original = createTestChatView(options = options)
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "conv-abandoned")
            bridge.javaClass.getMethod("onShowConversationList").invoke(bridge)
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(options = options)
        val selectedState = Bundle()
        try {
            restored.initialize(savedState)
            val webView = shadowOf(restored.chatWebView())
            assertEquals("none", initialConversation(webView).getJSONObject("target").getString("kind"))
            // The controller opened chat by default, so only the saved list state can bring the
            // embed back to the list.
            assertEquals("true", Uri.parse(webView.lastLoadedUrl).getQueryParameter("showConversationListByDefault"))
            // Picking a conversation from the list leaves the list behind again.
            val bridge = webView.getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onHideConversationList").invoke(bridge)
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "conv-selected")
            restored.setPageLoaded(true)
            restored.saveState(selectedState)
        } finally {
            restored.dispose()
        }

        val reselected = createTestChatView(options = options)
        try {
            reselected.initialize(selectedState)
            val webView = shadowOf(reselected.chatWebView())
            val target = initialConversation(webView).getJSONObject("target")
            assertEquals("conv-selected", target.getString("conversationID"))
            assertNull(Uri.parse(webView.lastLoadedUrl).getQueryParameter("showConversationListByDefault"))
        } finally {
            reselected.dispose()
        }
    }

    @Test
    fun newChatStartedFromTheListIsNotRecreatedOntoTheList() {
        val options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token")
        val original = createTestChatView(options = options)
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            // Opening the list clears the store first and then reports the list.
            bridge.javaClass.getMethod("clearStorage").invoke(bridge)
            bridge.javaClass.getMethod("onShowConversationList").invoke(bridge)
            // Starting a new chat from the list clears the store before the list reports hiding.
            bridge.javaClass.getMethod("clearStorage").invoke(bridge)
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(options = options)
        try {
            restored.initialize(savedState)
            val webView = shadowOf(restored.chatWebView())
            assertEquals("none", initialConversation(webView).getJSONObject("target").getString("kind"))
            assertNull(Uri.parse(webView.lastLoadedUrl).getQueryParameter("showConversationListByDefault"))
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun resumeStateIsNotRestoredForADifferentIdentity() {
        val original = createTestChatView(
            options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token"),
            conversationID = "external-123",
        )
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "external-123")
            bridge.javaClass.getMethod("onShowConversationList").invoke(bridge)
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        // The host hands the recreated view another user with the same explicit conversation, so
        // the previous user's list and consumed target must not apply to it.
        val restored = createTestChatView(
            options = AgentChatControllerOptions(name = "Test", userIdentityToken = "other-identity-token"),
            conversationID = "external-123",
        )
        try {
            restored.initialize(savedState)
            val webView = shadowOf(restored.chatWebView())
            val payload = initialConversation(webView)
            assertEquals("other-identity-token", payload.getString("userIdentityToken"))
            assertEquals("external-123", payload.getJSONObject("target").getString("conversationID"))
            assertNull(Uri.parse(webView.lastLoadedUrl).getQueryParameter("showConversationListByDefault"))
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun initialTargetIsNotReplayedAfterConversationListIsShown() {
        val options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token")
        for (initial in listOf(Pair("opaque-state", null), Pair(null, "external-123"))) {
            val (conversationState, conversationID) = initial
            val original = createTestChatView(
                options = options,
                conversationState = conversationState,
                conversationID = conversationID,
            )
            val savedState = Bundle()
            try {
                original.initialize()
                val webView = shadowOf(original.chatWebView())
                val bridge = webView.getJavascriptInterface("AndroidSDK")
                // Until the embed resolves the target, a reload still needs it.
                assertNotEquals("none", initialConversation(webView).getJSONObject("target").getString("kind"))
                bridge.javaClass.getMethod("onConversationStart", String::class.java)
                    .invoke(bridge, "conv-resolved")
                // Resolution alone does not consume the target: a recreation now resumes the live
                // conversation the embed reported.
                val resolvedTarget = initialConversation(webView).getJSONObject("target")
                assertEquals("conversationID", resolvedTarget.getString("kind"))
                assertEquals("conv-resolved", resolvedTarget.getString("conversationID"))
                bridge.javaClass.getMethod("onShowConversationList").invoke(bridge)
                original.setPageLoaded(true)
                original.saveState(savedState)
            } finally {
                original.dispose()
            }

            val restored = createTestChatView(
                options = options,
                conversationState = conversationState,
                conversationID = conversationID,
            )
            try {
                restored.initialize(savedState)
                val webView = shadowOf(restored.chatWebView())
                val payload = initialConversation(webView)
                assertEquals("none", payload.getJSONObject("target").getString("kind"))
                assertEquals("identity-token", payload.getString("userIdentityToken"))
                assertEquals("true", Uri.parse(webView.lastLoadedUrl).getQueryParameter("showConversationListByDefault"))
            } finally {
                restored.dispose()
            }
        }
    }

    @Test
    fun anonymousStateIsReplayedUntilTheConversationIsLeft() {
        val options = AgentChatControllerOptions(name = "Test")
        val original = createTestChatView(options = options, conversationState = "opaque-state")
        val savedState = Bundle()
        try {
            original.initialize()
            val webView = shadowOf(original.chatWebView())
            val bridge = webView.getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "conv-resolved")
            // Without an identity the embed cannot resume by ID and keeps resolved credentials in
            // memory, so the state stays the only way back into the conversation.
            assertEquals("state", initialConversation(webView).getJSONObject("target").getString("kind"))
            bridge.javaClass.getMethod("storeValue", String::class.java, String::class.java)
                .invoke(bridge, "embed-chat-test-token", """{"conversationEnded":false}""")
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(options = options, conversationState = "opaque-state")
        try {
            restored.initialize(savedState)
            val target = initialConversation(shadowOf(restored.chatWebView())).getJSONObject("target")
            assertEquals("none", target.getString("kind"))
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun conversationAbandonedByNewChatIsNotResumedAfterRecreation() {
        val options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token")
        val original = createTestChatView(options = options)
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "conv-abandoned")
            // New chat resets the embed's state and persists a record without a conversation ID.
            bridge.javaClass.getMethod("storeValue", String::class.java, String::class.java)
                .invoke(bridge, "embed-chat-test-token", """{"conversationEnded":false}""")
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(options = options)
        try {
            restored.initialize(savedState)
            val target = initialConversation(shadowOf(restored.chatWebView())).getJSONObject("target")
            assertEquals("none", target.getString("kind"))
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun activeConversationIsNotRestoredForADifferentAgent() {
        val options = AgentChatControllerOptions(name = "Test", userIdentityToken = "identity-token")
        val original = createTestChatView(options = options)
        val savedState = Bundle()
        try {
            original.initialize()
            val bridge = shadowOf(original.chatWebView()).getJavascriptInterface("AndroidSDK")
            bridge.javaClass.getMethod("onConversationStart", String::class.java)
                .invoke(bridge, "conv-other-agent")
            original.setPageLoaded(true)
            original.saveState(savedState)
        } finally {
            original.dispose()
        }

        val restored = createTestChatView(
            options = options,
            agentConfig = AgentConfig(token = "other-agent-token"),
        )
        try {
            restored.initialize(savedState)
            val target = initialConversation(shadowOf(restored.chatWebView())).getJSONObject("target")
            assertEquals("none", target.getString("kind"))
        } finally {
            restored.dispose()
        }
    }

    @Test
    fun legacyWebViewHistoryIsNotRestored() {
        val original = createTestChatView()
        val legacyUrl = "https://sierra.chat/agent/test-token/mobile?state=legacy-state"
        original.chatWebView().loadUrl(legacyUrl)
        shadowOf(original.chatWebView()).pushEntryToHistory(legacyUrl)
        original.setPageLoaded(true)
        val savedState = Bundle()
        original.saveState(savedState)
        savedState.remove("bridgeBootstrap")
        original.dispose()

        val restored = createTestChatView()
        try {
            restored.initialize(savedState)
            val webView = shadowOf(restored.chatWebView())
            assertTrue(webView.lastLoadedUrl.contains("/mobile"))
            assertCleanUrl(webView)
        } finally {
            restored.dispose()
        }
    }

    private fun initialConversation(webView: ShadowWebView): JSONObject {
        val bridge = webView.getJavascriptInterface("AndroidSDK")
        return JSONObject(bridge.javaClass.getMethod("getInitialConversation").invoke(bridge) as String)
    }

    private fun assertCleanUrl(webView: ShadowWebView) {
        val url = Uri.parse(webView.lastLoadedUrl)
        for (name in listOf("userIdentityToken", "state", "conversationID")) {
            assertNull(url.getQueryParameter(name))
        }
    }

    private fun loadedUrl(options: AgentChatControllerOptions): Uri =
        withLoadedChat(options) { Uri.parse(it.lastLoadedUrl) }

    private fun <T> withLoadedChat(
        options: AgentChatControllerOptions,
        conversationState: String? = null,
        conversationID: String? = null,
        verify: (ShadowWebView) -> T,
    ): T {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val activity = activityController.get()
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
            return verify(shadowOf(webView))
        } finally {
            activityController.pause().stop().destroy()
        }
    }
}
