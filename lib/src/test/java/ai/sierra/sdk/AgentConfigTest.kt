// Copyright Sierra

package ai.sierra.sdk

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgentConfigTest {
    @Test
    fun oauthAccessTokenIsNotPersistedInParcelableState() {
        val config = AgentConfig(
            token = "agent-token",
            target = "release-name",
            oauthAccessToken = "oauth-token",
        )
        val parcel = Parcel.obtain()

        try {
            parcel.writeParcelable(config, 0)
            parcel.setDataPosition(0)

            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<AgentConfig>(AgentConfig::class.java.classLoader)
            assertEquals("agent-token", restored?.token)
            assertEquals("release-name", restored?.target)
            assertNull(restored?.oauthAccessToken)
        } finally {
            parcel.recycle()
        }
    }
}
