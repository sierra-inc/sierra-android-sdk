// Copyright Sierra

package ai.sierra.sdk.compose

import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

/**
 * Activity-result registry that records launches instead of starting an activity and hands the
 * result back only when the test asks for it.
 *
 * Holding the result lets tests replace the requesting view while a chooser is still "open", which
 * a stubbed real launch cannot express.
 */
internal class TestActivityResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry
        get() = this

    var launchCount: Int = 0
        private set

    private var pendingRequestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        check(pendingRequestCode == null) {
            "A file chooser launch is already pending; deliver or drop it before launching again"
        }
        launchCount++
        pendingRequestCode = requestCode
    }

    /** Completes the most recent launch. Must run on the main thread. */
    fun deliverPendingResult(resultCode: Int, data: Intent?) {
        val requestCode = checkNotNull(pendingRequestCode) { "No file chooser launch is pending" }
        pendingRequestCode = null
        dispatchResult(requestCode, resultCode, data)
    }
}
