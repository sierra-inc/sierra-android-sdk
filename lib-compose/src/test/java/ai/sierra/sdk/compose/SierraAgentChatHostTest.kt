// Copyright Sierra

package ai.sierra.sdk.compose

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val HOST_KEY_A = "controller-a"
private const val HOST_KEY_B = "controller-b"
private const val SAVEABLE_KEY = "chat"
private const val CHAT_TAG = "chat-host"

private val FIRST_URI: Uri = Uri.parse("content://sierra.test/first")
private val SECOND_URI: Uri = Uri.parse("content://sierra.test/second")

/**
 * Hermetic coverage of the Compose host contract in [ManagedAgentChatView]. These tests use a fake
 * view, make no network calls, and run on the JVM, so they are safe to gate pull requests on.
 *
 * Native graphics are required because the layout case asserts the hosted view is really measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SierraAgentChatHostTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val adapter = FakeChatViewAdapter()
    private val activityResultRegistry = TestActivityResultRegistry()

    private lateinit var scenario: ActivityScenario<SierraAgentChatHostTestActivity>

    @Before
    fun launchHostActivity() {
        // The host activity lives in this source set, so it is in no merged manifest. Robolectric
        // refuses to launch an activity the package manager does not know about.
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).addActivityIfNotPresent(
            ComponentName(application, SierraAgentChatHostTestActivity::class.java),
        )
        ComposeHostTestContent.activityResultRegistryOwner = activityResultRegistry
        ComposeHostTestContent.content = null
        scenario = ActivityScenario.launch(SierraAgentChatHostTestActivity::class.java)
    }

    @After
    fun closeHostActivity() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        ComposeHostTestContent.content = null
        ComposeHostTestContent.activityResultRegistryOwner = null
    }

    @Test
    fun createsAndInitializesViewOnce() {
        val recompositions = mutableStateOf(0)
        setHostContent {
            ManagedFakeChatHost(
                adapter = adapter,
                hostKey = HOST_KEY_A,
                modifier = Modifier.testTag("$CHAT_TAG-${recompositions.value}"),
            )
        }

        repeat(3) { iteration ->
            composeRule.runOnUiThread { recompositions.value = iteration + 1 }
            composeRule.waitForIdle()
        }

        assertEquals(1, adapter.created.size)
        assertEquals(1, adapter.latest.initializeCount)
    }

    @Test
    fun hostViewIsMeasuredWithRealSize() {
        setHostContent { ManagedFakeChatHost(adapter, HOST_KEY_A) }

        // An AndroidView that collapses to zero height is the classic interop failure for a chat
        // host placed inside a scrollable parent.
        scenario.onActivity {
            assertTrue(adapter.latest.isLaidOut)
            assertTrue(adapter.latest.height > 0)
        }
    }

    @Test
    fun removingHostDisposesView() {
        val visible = mutableStateOf(true)
        setHostContent {
            if (visible.value) {
                ManagedFakeChatHost(adapter, HOST_KEY_A)
            }
        }
        val view = adapter.latest

        setVisible(visible, false)

        assertEquals(1, view.disposeCount)
    }

    @Test
    fun removingHostWithoutAStateHolderDiscardsState() {
        val visible = mutableStateOf(true)
        setHostContent {
            if (visible.value) {
                ManagedFakeChatHost(adapter, HOST_KEY_A)
            }
        }

        setVisible(visible, false)
        setVisible(visible, true)

        // Nothing retains the saveable registry entry across the removal, so the replacement starts
        // a fresh conversation. Callers that want the conversation back keep the host composed or
        // wrap it in a SaveableStateProvider, as the next test does.
        assertEquals(2, adapter.created.size)
        assertEquals(emptyList<String>(), adapter.latest.restoredSentinels)
        assertEquals(1, adapter.latest.initializeCount)
    }

    @Test
    fun saveableStateProviderRestoresReleasedViewState() {
        val visible = mutableStateOf(true)
        setHostContent {
            val stateHolder = rememberSaveableStateHolder()
            if (visible.value) {
                stateHolder.SaveableStateProvider(SAVEABLE_KEY) {
                    ManagedFakeChatHost(adapter, HOST_KEY_A)
                }
            }
        }
        val released = adapter.latest

        setVisible(visible, false)
        setVisible(visible, true)

        assertEquals(2, adapter.created.size)
        assertNotSame(released, adapter.latest)
        assertTrue(released.saveStateCount >= 1)
        assertEquals(listOf(released.sentinel), adapter.latest.restoredSentinels)
        assertEquals(1, adapter.latest.initializeCount)
    }

    @Test
    fun changingInstanceKeyReplacesAndDisposesOldView() {
        val instanceKey = mutableStateOf(HOST_KEY_A)
        setHostContent {
            ManagedFakeChatHost(
                adapter = adapter,
                hostKey = SAVEABLE_KEY,
                instanceKey = instanceKey.value,
            )
        }
        val first = adapter.latest

        composeRule.runOnUiThread { instanceKey.value = HOST_KEY_B }
        composeRule.waitForIdle()

        assertEquals(2, adapter.created.size)
        assertEquals(1, first.disposeCount)
        assertEquals(1, adapter.latest.initializeCount)
        assertEquals(0, adapter.latest.disposeCount)
        // A different instance is a different chat, so the replacement must not adopt the previous
        // one's conversation.
        assertEquals(emptyList<String>(), adapter.latest.restoredSentinels)
    }

    @Test
    fun recreationAfterHostKeyChangeRestoresTheReplacementsState() {
        val hostKey = mutableStateOf(HOST_KEY_A)
        setHostContent { ManagedFakeChatHost(adapter, hostKey.value) }

        composeRule.runOnUiThread { hostKey.value = HOST_KEY_B }
        composeRule.waitForIdle()
        val replacement = adapter.latest

        scenario.recreate()
        composeRule.waitForIdle()

        // The released view unregistered its saved-state provider when it was disposed, so the only
        // state the recreated activity can see belongs to the view that is actually on screen.
        assertEquals(listOf(replacement.sentinel), adapter.latest.restoredSentinels)
    }

    @Test
    fun activityRecreationRestoresState() {
        setHostContent { ManagedFakeChatHost(adapter, HOST_KEY_A) }
        val released = adapter.latest

        scenario.recreate()
        composeRule.waitForIdle()

        assertEquals(2, adapter.created.size)
        assertNotSame(released, adapter.latest)
        assertEquals(listOf(released.sentinel), adapter.latest.restoredSentinels)
        assertEquals(1, adapter.latest.initializeCount)
        assertEquals(1, released.disposeCount)
    }

    @Test
    fun activityRecreationRestoresStateWithANewEquivalentInstance() {
        setHostContent {
            ManagedFakeChatHost(
                adapter = adapter,
                hostKey = HOST_KEY_A,
                instanceKey = remember { Any() },
            )
        }
        val released = adapter.latest

        scenario.recreate()
        composeRule.waitForIdle()

        assertEquals(2, adapter.created.size)
        assertNotSame(released, adapter.latest)
        assertEquals(listOf(released.sentinel), adapter.latest.restoredSentinels)
        assertEquals(1, released.disposeCount)
    }

    @Test
    fun fileChooserResultTargetsCurrentView() {
        setHostContent { ManagedFakeChatHost(adapter, HOST_KEY_A) }
        val view = adapter.latest

        composeRule.runOnUiThread { view.requestFileChooser() }
        composeRule.waitForIdle()
        deliverFileChooserResult(FIRST_URI)

        assertEquals(1, activityResultRegistry.launchCount)
        assertEquals(listOf(listOf(FIRST_URI)), view.receivedFileResults)
    }

    @Test
    fun fileChooserResultAfterRecreationTargetsRestoredView() {
        setHostContent { ManagedFakeChatHost(adapter, HOST_KEY_A) }
        val released = adapter.latest

        composeRule.runOnUiThread { released.requestFileChooser() }
        composeRule.waitForIdle()

        // The chooser is still open while the activity recreates, so the result must reach the
        // restored view. That routing relies on the launcher re-registering under the same saved
        // key after recreation; a launcher keyed per composition would drop the result instead.
        scenario.recreate()
        composeRule.waitForIdle()
        val restored = adapter.latest
        deliverFileChooserResult(FIRST_URI)

        assertEquals(emptyList<List<Uri>>(), released.receivedFileResults)
        assertEquals(listOf(listOf(FIRST_URI)), restored.receivedFileResults)
    }

    @Test
    fun lateFileChooserResultDoesNotTargetReleasedView() {
        val instanceKey = mutableStateOf(HOST_KEY_A)
        setHostContent {
            ManagedFakeChatHost(
                adapter = adapter,
                hostKey = SAVEABLE_KEY,
                instanceKey = instanceKey.value,
            )
        }
        val released = adapter.latest

        composeRule.runOnUiThread { released.requestFileChooser() }
        composeRule.runOnUiThread { instanceKey.value = HOST_KEY_B }
        composeRule.waitForIdle()
        val replacement = adapter.latest

        // A replacement cannot start a second indistinguishable request while the released
        // instance still owns one. Its callback is cancelled rather than receiving the old URI.
        composeRule.runOnUiThread { replacement.requestFileChooser() }
        composeRule.waitForIdle()
        assertEquals(1, activityResultRegistry.launchCount)
        assertEquals(listOf(emptyList<Uri>()), replacement.receivedFileResults)

        deliverFileChooserResult(FIRST_URI)

        assertEquals(emptyList<List<Uri>>(), released.receivedFileResults)
        assertEquals(listOf(emptyList<Uri>()), replacement.receivedFileResults)

        composeRule.runOnUiThread { replacement.requestFileChooser() }
        composeRule.waitForIdle()
        deliverFileChooserResult(SECOND_URI)

        assertEquals(
            listOf(emptyList(), listOf(SECOND_URI)),
            replacement.receivedFileResults,
        )
    }

    @Test
    fun releaseIsIdempotentAcrossRepeatedRemoval() {
        val visible = mutableStateOf(true)
        setHostContent {
            if (visible.value) {
                ManagedFakeChatHost(adapter, HOST_KEY_A)
            }
        }

        repeat(5) {
            setVisible(visible, false)
            setVisible(visible, true)
        }

        assertEquals(6, adapter.created.size)
        adapter.created.dropLast(1).forEach { assertEquals(1, it.disposeCount) }
        assertEquals(0, adapter.latest.disposeCount)
    }

    private fun setHostContent(content: @Composable () -> Unit) {
        ComposeHostTestContent.content = content
        scenario.onActivity { activity ->
            activity.renderContent()
        }
        composeRule.waitForIdle()
    }

    private fun setVisible(visible: MutableState<Boolean>, value: Boolean) {
        composeRule.runOnUiThread { visible.value = value }
        composeRule.waitForIdle()
    }

    private fun deliverFileChooserResult(uri: Uri) {
        composeRule.runOnUiThread {
            activityResultRegistry.deliverPendingResult(
                Activity.RESULT_OK,
                Intent().setData(uri),
            )
        }
        composeRule.waitForIdle()
    }
}

@Composable
private fun ManagedFakeChatHost(
    adapter: FakeChatViewAdapter,
    hostKey: Any,
    instanceKey: Any = hostKey,
    modifier: Modifier = Modifier,
) {
    ManagedAgentChatView(
        hostKey = hostKey,
        instanceKey = instanceKey,
        modifier = modifier,
        adapter = adapter,
    )
}
