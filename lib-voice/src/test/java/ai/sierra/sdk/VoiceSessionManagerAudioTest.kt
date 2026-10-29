// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class VoiceSessionManagerAudioTest {
    @Test
    @Suppress("DEPRECATION")
    fun refusedStartReportsAudioErrorAndPreservesExistingCallAudio() {
        AppContextHolder.applicationContext = RuntimeEnvironment.getApplication()
        val audioManager = AppContextHolder.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_CALL
        audioManager.isSpeakerphoneOn = false
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val errors = mutableListOf<Throwable>()
        var endCount = 0
        val session = VoiceSessionManager(
            config = AgentConfig(token = "test-token"),
            delegate = object : VoiceSessionDelegate {
                override fun onReceiveCredentials(conversationID: String, encryptionKey: String?) = Unit
                override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) = Unit
                override fun onChangeState(state: VoiceSessionManager.State) = Unit
                override fun onError(error: Throwable) {
                    errors.add(error)
                }
                override fun onEnd() {
                    endCount += 1
                }
            },
        )

        session.connect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VoiceAudioException.Reason.UNAVAILABLE, (errors.single() as VoiceAudioException).reason)
        assertEquals(0, endCount)
        assertEquals(VoiceSessionManager.State.ENDED, session.currentState)
        assertEquals(AudioManager.MODE_IN_CALL, audioManager.mode)
        assertFalse(audioManager.isSpeakerphoneOn)
    }
}
