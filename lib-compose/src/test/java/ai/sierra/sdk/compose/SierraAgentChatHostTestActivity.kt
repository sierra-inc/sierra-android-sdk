// Copyright Sierra

package ai.sierra.sdk.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Host activity for the Compose host contract tests. It lives in the test source set, so it reaches
 * neither the published artifact nor a consumer's debug build, and it sets its own content in
 * [onCreate] so the composition survives activity recreation.
 */
internal class SierraAgentChatHostTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderContent()
    }

    fun renderContent() {
        val content = ComposeHostTestContent.content ?: return
        val activityResultRegistryOwner =
            requireNotNull(ComposeHostTestContent.activityResultRegistryOwner)
        setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
            ) {
                content()
            }
        }
    }
}

internal object ComposeHostTestContent {
    var content: (@Composable () -> Unit)? = null
    var activityResultRegistryOwner: ActivityResultRegistryOwner? = null
}
