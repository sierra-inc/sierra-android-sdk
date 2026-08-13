// Copyright Sierra

package ai.sierra.sdk.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The chat view operations [ManagedAgentChatView] drives.
 *
 * The production implementation wraps [ai.sierra.sdk.AgentChatController]. Tests supply a fake so
 * the host can be exercised on the JVM with no WebView, network, backend, or agent token.
 *
 * Saved state is deliberately absent: the hosted view carries a stable id, so `AndroidView` saves
 * and restores chat state through the view's own hierarchy state.
 */
internal interface ChatViewAdapter<T : View> {
    fun createView(context: Context, launchFileChooser: (Intent) -> Unit): T

    fun dispose(view: T)

    /**
     * Hands a chooser activity result to [view]. Production parses it with
     * `WebChromeClient.FileChooserParams`, which needs a WebView provider, so the parse sits behind
     * the adapter to keep the routing testable off-device.
     */
    fun onFileChooserResult(view: T, resultCode: Int, data: Intent?)
}

/**
 * Single source of truth for Compose's ownership of a chat view: creation, disposal, and routing of
 * file chooser results back to the view that asked for one.
 *
 * @param hostKey Stable identity of this host's saved-state slot.
 * @param instanceKey Identity of the current chat view instance. Changing it releases the current
 *   view and creates a replacement without changing the saved-state slot.
 */
@Composable
internal fun <T : View> ManagedAgentChatView(
    hostKey: Any,
    instanceKey: Any,
    modifier: Modifier,
    adapter: ChatViewAdapter<T>,
) {
    key(hostKey) {
        // Everything below is created and torn down with the view it serves. A chooser result that
        // arrives after that view is released finds no registered launcher and is dropped.
        val viewSlot = remember { ViewSlot<T>() }
        val fileChooserLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            viewSlot.consumeResultTarget()?.let {
                adapter.onFileChooserResult(it, result.resultCode, result.data)
            }
        }

        AndroidView(
            factory = { context ->
                ChatViewHost<T>(context).also { host ->
                    host.bind(instanceKey, adapter) { intent ->
                        viewSlot.launch(instanceKey, intent, fileChooserLauncher::launch, adapter)
                    }
                    viewSlot.bind(instanceKey, host.chatView)
                }
            },
            modifier = modifier,
            update = { host ->
                host.bind(instanceKey, adapter) { intent ->
                    viewSlot.launch(instanceKey, intent, fileChooserLauncher::launch, adapter)
                }
                viewSlot.bind(instanceKey, host.chatView)
            },
            onRelease = { host ->
                val releasedView = host.chatView
                host.release()
                if (viewSlot.view === releasedView) {
                    viewSlot.bind(null, null)
                }
            },
        )
    }
}

/** Keeps Compose's saved-state slot stable while replacing controller-bound child views. */
private class ChatViewHost<T : View>(context: Context) : FrameLayout(context) {
    var chatView: T? = null
        private set

    private var instanceKey: Any? = null
    private var adapter: ChatViewAdapter<T>? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    fun bind(
        instanceKey: Any,
        adapter: ChatViewAdapter<T>,
        launchFileChooser: (Intent) -> Unit,
    ) {
        if (this.instanceKey === instanceKey && this.adapter === adapter) {
            return
        }
        release()
        this.instanceKey = instanceKey
        this.adapter = adapter
        chatView = adapter.createView(context, launchFileChooser).also { addView(it) }
    }

    fun release() {
        chatView?.let { view ->
            adapter?.dispose(view)
            removeView(view)
        }
        chatView = null
        instanceKey = null
        adapter = null
    }
}

/** Tracks the current view and the instance that owns the single in-flight chooser result. */
private class ViewSlot<T : View> {
    var view: T? = null
        private set

    private var instanceKey: Any? = null
    private var inFlightInstanceKey: Any? = null
    private var hasInFlightResult = false

    fun bind(instanceKey: Any?, view: T?) {
        this.instanceKey = instanceKey
        this.view = view
    }

    fun launch(
        ownerInstanceKey: Any,
        intent: Intent,
        launcher: (Intent) -> Unit,
        adapter: ChatViewAdapter<T>,
    ) {
        if (hasInFlightResult) {
            if (ownerInstanceKey === instanceKey) {
                view?.let { adapter.onFileChooserResult(it, Activity.RESULT_CANCELED, null) }
            }
            return
        }
        hasInFlightResult = true
        inFlightInstanceKey = ownerInstanceKey
        launcher(intent)
    }

    fun consumeResultTarget(): T? {
        // After Activity recreation this slot is new and has no owner marker, so the restored view
        // receives the registry's pending result. Within one Activity, require the same instance.
        val target = if (!hasInFlightResult || inFlightInstanceKey === instanceKey) view else null
        hasInFlightResult = false
        inFlightInstanceKey = null
        return target
    }
}
