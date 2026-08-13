// Copyright Sierra

package ai.sierra.sdk

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatViewDeviceSavedStateTest {
    @Test
    fun sdkParcelableSurvivesDeviceClassLoaderRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            verifySavedStateRoundTrip()
        }
    }

    private fun verifySavedStateRoundTrip() {
        val source = createChatView()
        source.setPageLoaded(true)
        val saved = SparseArray<Parcelable>()
        source.saveHierarchyState(saved)

        val marshalled = marshalThenUnmarshal(
            requireNotNull(saved.get(R.id.sierra_agent_chat_view)),
        )
        val restoredState = SparseArray<Parcelable>().apply {
            put(R.id.sierra_agent_chat_view, marshalled)
        }
        val restored = createChatView()

        // This reads the nested AgentChatFragmentArgs from SavedState. A framework-only Bundle
        // class loader throws BadParcelableException here on a device even though Robolectric passes.
        restored.restoreHierarchyState(restoredState)
        restored.initialize()

        assertEquals(View.GONE, restored.loadingSpinner().visibility)
        assertNull(restored.chatWebView().url)
    }

    private fun createChatView(): AgentChatView = AgentChatView(
        context = ApplicationProvider.getApplicationContext<Context>(),
        agentConfig = AgentConfig(token = "test-token"),
        options = AgentChatControllerOptions(name = "Test Agent"),
        conversationState = null,
        listener = null,
        storage = null,
        fileChooserLauncher = null,
        onConversationEndedInternal = null,
        onDispose = null,
        viewId = R.id.sierra_agent_chat_view,
    )

    private fun marshalThenUnmarshal(state: Parcelable): Parcelable {
        val out = Parcel.obtain()
        val bytes = try {
            out.writeParcelable(state, 0)
            out.marshall()
        } finally {
            out.recycle()
        }

        val incoming = Parcel.obtain()
        return try {
            incoming.unmarshall(bytes, 0, bytes.size)
            incoming.setDataPosition(0)
            requireNotNull(incoming.readParcelable(AgentChatView::class.java.classLoader))
        } finally {
            incoming.recycle()
        }
    }

    private fun AgentChatView.chatWebView(): WebView =
        (0 until childCount).map(::getChildAt).filterIsInstance<WebView>().single()

    private fun AgentChatView.loadingSpinner(): ProgressBar =
        (0 until childCount).map(::getChildAt).filterIsInstance<ProgressBar>().single()
}
