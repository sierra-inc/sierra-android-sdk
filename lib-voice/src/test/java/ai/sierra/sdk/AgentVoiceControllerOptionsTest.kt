// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgentVoiceControllerOptionsTest {
    @Test
    fun compactControlsAreOptInAndSurviveParcelableRoundTrip() {
        assertFalse(AgentVoiceControllerOptions(name = "Test").useCompactControls)
        val options = AgentVoiceControllerOptions(
            name = "Test",
            userIdentityToken = "user-identity-token",
            useCompactControls = true,
        )
        val parcel = Parcel.obtain()

        try {
            parcel.writeParcelable(options, 0)
            parcel.setDataPosition(0)

            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<AgentVoiceControllerOptions>(
                AgentVoiceControllerOptions::class.java.classLoader
            )
            assertTrue(restored?.useCompactControls == true)
            assertEquals("user-identity-token", restored?.userIdentityToken)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun voicePlaceholderStyleSurvivesParcelableRoundTrip() {
        val style = AgentVoiceStyle(
            voicePlaceholderFontResId = 123,
            voicePlaceholderTextSizeSp = 32f,
            voicePlaceholderSpacingDp = 64,
        )
        val options = AgentVoiceControllerOptions(name = "Test", voiceStyle = style)
        val parcel = Parcel.obtain()

        try {
            parcel.writeParcelable(options, 0)
            parcel.setDataPosition(0)

            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<AgentVoiceControllerOptions>(
                AgentVoiceControllerOptions::class.java.classLoader
            )
            assertEquals(style, restored?.voiceStyle)
        } finally {
            parcel.recycle()
        }
    }
}
