// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Color
import android.os.Bundle
import android.os.Parcel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChatButtonStyleTest {
    @Test
    fun buttonOptionsSurvivePublicParcelableRoundTrip() {
        val style = ChatButtonStyle(
            backgroundColor = Color.TRANSPARENT,
            borderWidth = "0px",
            width = "100%",
            iconSVG = "<svg/>",
        )
        val options = AgentChatControllerOptions(
            name = "Agent",
            footerEndConversationButtonStyle = style,
            messageInputPresetAction = MessageInputPresetAction(
                "Help",
                MessageInputPresetAction.ClientEvent(MessageInputPresetAction.ClientEvent.Message("Help")),
                style = ChatButtonStyle(width = "150px"),
            ),
        )
        val restored = roundTrip(Bundle().apply {
            putParcelable("options", options)
            putParcelable("style", style)
        })
        assertEquals(options, restored.getParcelable<AgentChatControllerOptions>("options"))
        assertEquals(style, restored.getParcelable<ChatButtonStyle>("style"))
    }

    private fun roundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            val bytes = parcel.marshall()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(AgentChatControllerOptions::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun buttonOptionsSerializeAsWebObjectsAndKeepDefaults() {
        assertNull(ChatButtonStyle().toJSONString())
        assertNull(AgentChatControllerOptions(name = "Agent").messageInputPresetAction)
        val style = ChatButtonStyle(alignment = ChatButtonStyle.Alignment.END,
            backgroundColor = Color.BLACK, textColor = Color.WHITE, borderColor = Color.RED,
            borderWidth = "0px", height = "44px", width = "100%",
            padding = "0.5em", borderRadius = "22px", iconSVG = "<svg/>")
        val action = MessageInputPresetAction("Help", MessageInputPresetAction.ClientEvent(
            MessageInputPresetAction.ClientEvent.Message("Help & advice?")), 1, style)
        val json = JSONObject(action.toJSONString())
        assertEquals("message", json.getJSONObject("clientEvent").getString("type"))
        assertEquals("Help & advice?", json.getJSONObject("clientEvent").getJSONObject("message").getString("content"))
        val button = json.getJSONObject("style")
        assertEquals("100%", button.getString("width"))
        assertEquals("end", button.getString("alignment"))
        assertEquals("#000000", button.getString("backgroundColor"))
        assertEquals("#FFFFFF", button.getString("textColor"))
        assertEquals("#FF0000", button.getString("borderColor"))
        assertEquals("0px", button.getString("borderWidth"))
        assertEquals("44px", button.getString("height"))
        assertEquals("0.5em", button.getString("padding"))
        assertEquals("22px", button.getString("borderRadius"))
        assertEquals("<svg/>", button.getString("iconSVG"))
        assertEquals("150px", ChatButtonStyle(width = "150px").toJSON()["width"])
    }
}
