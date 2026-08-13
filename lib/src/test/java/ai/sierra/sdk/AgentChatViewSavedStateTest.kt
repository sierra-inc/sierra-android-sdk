// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the one thing that makes `AgentChatView`'s own saved-state plumbing reachable: a stable view
 * id. Android's [View.dispatchSaveInstanceState] skips any view whose id is unset, so deleting the id
 * assignment silently disables [AgentChatView.onSaveInstanceState] and every host that relies on it.
 *
 * The view is never initialized here, so no URL is loaded and no token is used; construction only
 * needs a syntactically valid [AgentConfig].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatViewSavedStateTest {
    @Test
    fun saveHierarchyStateStoresStateUnderTheStableViewId() {
        val view = createTestChatView()

        assertEquals(R.id.sierra_agent_chat_view, view.id)
        assertNotEquals("id must not be View.NO_ID", View.NO_ID, view.id)

        val container = SparseArray<Parcelable>()
        view.saveHierarchyState(container)

        // The entry must land under the stable id. If the id assignment is deleted, dispatchSave-
        // InstanceState skips the view and this container comes back empty.
        assertEquals(1, container.size())
        assertNotNull(
            "no saved state stored under R.id.sierra_agent_chat_view",
            container.get(R.id.sierra_agent_chat_view),
        )
    }

    @Test
    fun restoreHierarchyStateReachesOnRestoreInstanceState() {
        val saved = SparseArray<Parcelable>()
        createTestChatView().saveHierarchyState(saved)

        // A fresh view stands in for the one a recreated activity builds. It is deliberately never
        // initialized, so nothing has loaded a URL into its WebView yet.
        val restored = createTestChatView()
        assertNull(shadowOf(restored.chatWebView()).lastLoadedUrl)

        restored.restoreHierarchyState(saved)

        // Restoration is intentionally deferred until attachment so hosts can finish restoring the
        // hierarchy before an incompatible state falls back to loading a fresh chat.
        assertNull(shadowOf(restored.chatWebView()).lastLoadedUrl)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        activity.get().setContentView(restored)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // onRestoreInstanceState routes uninitialized views into initialize(), which loads the chat
        // URL. Without the stable id, dispatchRestoreInstanceState skips the view, nothing loads, and
        // this assertion fails.
        assertNotNull(
            "restoreHierarchyState did not reach onRestoreInstanceState",
            shadowOf(restored.chatWebView()).lastLoadedUrl,
        )
        activity.close()
    }

    /**
     * Process death is the only path that actually marshals the saved state, so it is the only path
     * that runs `SavedState`'s Parcel constructor. In-process activity recreation hands the same
     * SavedState instance back by reference, which is why rotation exercises none of this.
     *
     * This covers the structural contract only: that the chat state is written, read back, and still
     * matches the view's args. It cannot cover the class loader `readBundle` is given, because
     * Robolectric loads framework and SDK classes with a single sandbox loader -- so
     * `Bundle::class.java.classLoader` resolves `AgentChatFragmentArgs` here, while on a device it is
     * the boot loader and throws BadParcelableException. Verified: this test passes against that bug.
     * `SavedState` must keep using `javaClass.classLoader`; only an instrumented test can catch it.
     */
    @Test
    fun savedStateSurvivesAMarshalRoundTrip() {
        val source = createTestChatView()
        // saveState only records restorable args once the page has loaded, which is the state a
        // backgrounded chat is in when the process is killed.
        source.setPageLoaded(true)
        val container = SparseArray<Parcelable>()
        source.saveHierarchyState(container)

        val marshalled = marshalThenUnmarshal(container.get(R.id.sierra_agent_chat_view))

        val restoredContainer = SparseArray<Parcelable>()
        restoredContainer.put(R.id.sierra_agent_chat_view, marshalled)
        val restored = createTestChatView()
        restored.restoreHierarchyState(restoredContainer)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        activity.get().setContentView(restored)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // Reading the restored args is what unparcels the nested chat-state bundle, and only a read
        // that succeeds and matches reveals the web content instead of loading the chat from scratch.
        // A restore that cannot read them throws BadParcelableException on the way here.
        assertEquals(
            "marshalled state did not restore; the view fell back to a fresh load",
            View.GONE,
            restored.loadingSpinner().visibility,
        )
        assertNull(
            "restoring marshalled state loaded the chat URL instead of the saved WebView state",
            shadowOf(restored.chatWebView()).lastLoadedUrl,
        )
        activity.close()
    }

    /** Round trips through raw bytes the way the system does when it revives a killed process. */
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
            // The framework hands in the loader that can see the activity's classes; mirror that.
            requireNotNull(incoming.readParcelable(AgentChatView::class.java.classLoader))
        } finally {
            incoming.recycle()
        }
    }
}
