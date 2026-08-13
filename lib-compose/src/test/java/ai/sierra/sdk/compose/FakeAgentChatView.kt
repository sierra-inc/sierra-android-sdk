// Copyright Sierra

package ai.sierra.sdk.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup

internal const val FAKE_SUPER_STATE_KEY = "superState"
internal const val FAKE_SENTINEL_KEY = "fakeSentinel"

/**
 * Stand-in for `AgentChatView` that records the lifecycle calls [ManagedAgentChatView] makes,
 * without loading a WebView or contacting a backend.
 *
 * It mirrors the two mechanisms the host depends on:
 * - a stable view id plus [onSaveInstanceState] / [onRestoreInstanceState], so `AndroidView` can
 *   carry chat state across recreation the way it does for the real view;
 * - initialization armed for attachment rather than performed eagerly, so restored state wins the
 *   race and initialization still happens exactly once when there is nothing to restore.
 */
internal class FakeAgentChatView(
    context: Context,
    private val launchFileChooser: (Intent) -> Unit,
) : View(context) {
    /** Written into the saved state so a restoration can be attributed to a specific view. */
    var sentinel: String = ""

    var initializeCount: Int = 0
        private set
    var saveStateCount: Int = 0
        private set
    var disposeCount: Int = 0
        private set

    val restoredSentinels: MutableList<String> = mutableListOf()
    val receivedFileResults: MutableList<List<Uri>> = mutableListOf()

    private var initialized = false
    private var initializeOnAttach = false

    init {
        // The same id the real view sets, for the same reason: Android's view-state saving skips a
        // view whose id is unset, which would leave AndroidView with nothing to save.
        id = ai.sierra.sdk.R.id.sierra_agent_chat_view
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    /** Mirrors `AgentChatView.initializeWhenAttached`, which `createView` calls. */
    fun initializeWhenAttached() {
        if (initialized) {
            return
        }
        initializeOnAttach = true
        if (isAttachedToWindow) {
            markInitialized()
        }
    }

    /** Records every call, unlike the real view, so double disposal is visible to tests. */
    fun dispose() {
        disposeCount++
    }

    fun requestFileChooser() {
        launchFileChooser(Intent(Intent.ACTION_GET_CONTENT).setType("*/*"))
    }

    fun onFileChooserResult(uris: Array<Uri>?) {
        receivedFileResults.add(uris?.toList().orEmpty())
    }

    override fun onSaveInstanceState(): Parcelable {
        saveStateCount++
        return Bundle().apply {
            putParcelable(FAKE_SUPER_STATE_KEY, super.onSaveInstanceState())
            putString(FAKE_SENTINEL_KEY, sentinel)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        val bundle = state as? Bundle
        super.onRestoreInstanceState(bundle?.getParcelable(FAKE_SUPER_STATE_KEY))
        if (bundle == null) {
            return
        }
        restoredSentinels.add(bundle.getString(FAKE_SENTINEL_KEY).orEmpty())
        markInitialized()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (initializeOnAttach) {
            markInitialized()
        }
    }

    private fun markInitialized() {
        if (initialized) {
            return
        }
        initialized = true
        initializeOnAttach = false
        initializeCount++
    }
}

/**
 * Drives [ManagedAgentChatView] with fakes. Creates them the way `AgentChatController.createView`
 * does and retains every one, so tests can assert on views that have already been released.
 *
 * The real view's compatibility check has no counterpart here: `AgentChatView` routes restored state
 * through `restoreCompatibleState`, which rejects state saved for a different agent configuration.
 * That check belongs to `lib` and is not covered by this suite.
 */
internal class FakeChatViewAdapter : ChatViewAdapter<FakeAgentChatView> {
    val created: MutableList<FakeAgentChatView> = mutableListOf()

    val latest: FakeAgentChatView
        get() = created.last()

    override fun createView(
        context: Context,
        launchFileChooser: (Intent) -> Unit,
    ): FakeAgentChatView = FakeAgentChatView(context, launchFileChooser).also {
        it.sentinel = "view-${created.size}"
        created.add(it)
        it.initializeWhenAttached()
    }

    override fun dispose(view: FakeAgentChatView) {
        view.dispose()
    }

    /**
     * Reproduces the single-URI shape `WebChromeClient.FileChooserParams.parseResult` returns.
     * Production delegates to that platform call, which needs a WebView provider; that one line is
     * why the parse sits in the adapter rather than in the host.
     */
    override fun onFileChooserResult(view: FakeAgentChatView, resultCode: Int, data: Intent?) {
        val uris = if (resultCode == Activity.RESULT_OK) {
            data?.data?.let { arrayOf(it) }
        } else {
            null
        }
        view.onFileChooserResult(uris)
    }
}
