// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgentVoiceControllerErrorStateTest {
    @Test
    fun errorDisablesSessionControlsButKeepsExitAvailable() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        lateinit var muteButton: View
        lateinit var endButton: View
        val options = AgentVoiceControllerOptions(name = "Voice", hideTitleBar = true).apply {
            muteButtonProvider = { context -> View(context).also { muteButton = it } }
            unmuteButtonProvider = { context -> View(context) }
            endCallButtonProvider = { context -> View(context).also { endButton = it } }
        }
        val callbacks = CapturingVoiceCallbacks()
        val controller = AgentVoiceController(Agent(AgentConfig(token = "test-token")), options).apply {
            voiceCallbacks = callbacks
        }

        val fragment = controller.createFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        (fragment as VoiceSessionDelegate).onChangeState(VoiceSessionManager.State.ENDED)
        fragment.onChangeState(VoiceSessionManager.State.LISTENING)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, callbacks.errorCount)
        assertFalse(muteButton.isEnabled)
        assertTrue(endButton.isEnabled)

        endButton.performClick()

        assertEquals(1, callbacks.endCount)
    }

    private class CapturingVoiceCallbacks : VoiceCallbacks {
        var errorCount = 0
        var endCount = 0

        override fun onVoiceEnded() {
            endCount += 1
        }

        override fun onVoiceError(error: Throwable) {
            errorCount += 1
        }
    }
}
