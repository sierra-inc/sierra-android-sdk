// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

public class MuteButtonPill @JvmOverloads constructor(
    context: Context,
    @ColorInt backgroundColor: Int,
    @ColorInt iconColor: Int,
    @DrawableRes muteIconResId: Int? = null,
    title: String = "Mute",
    layout: VoiceControlButtonLayout = VoiceControlButtonLayout.PILL,
    attrs: AttributeSet? = null,
    // Appended rather than grouped with the other colors so the @JvmOverloads signatures existing
    // hosts compile against keep working.
    @ColorInt waveformUserColor: Int = DEFAULT_VOICE_WAVEFORM_USER_COLOR,
    @ColorInt waveformAgentColor: Int = DEFAULT_VOICE_WAVEFORM_AGENT_COLOR,
) : LinearLayout(context, attrs), VoiceMuteLevelDisplaying {
    private val iconContainer = FrameLayout(context)
    private var audioLevelView: VoiceAudioLevelView? = null

    init {
        configurePillButton(backgroundColor = backgroundColor, contentDescriptionText = title, layout = layout)
        addIconAndLabel(title = title, iconColor = iconColor, layout = layout)

        if (muteIconResId != null) {
            iconContainer.addView(staticIcon(context, muteIconResId, iconColor, MUTE_ICON_WIDTH_DP, MUTE_ICON_HEIGHT_DP))
        } else {
            val levelView = VoiceAudioLevelView(context).apply {
                micColor = iconColor
                inputColor = waveformUserColor
                outputColor = waveformAgentColor
                layoutParams = FrameLayout.LayoutParams(MUTE_ICON_WIDTH_DP.dp, MUTE_ICON_HEIGHT_DP.dp, Gravity.CENTER)
            }
            iconContainer.addView(levelView)
            audioLevelView = levelView
        }
    }

    override fun setInputLevel(level: Float) {
        audioLevelView?.setInputLevel(level)
    }

    override fun setOutputLevel(level: Float) {
        audioLevelView?.setOutputLevel(level)
    }

    override fun resetLevels() {
        audioLevelView?.resetLevels()
    }

    private fun addIconAndLabel(
        title: String,
        @ColorInt iconColor: Int,
        layout: VoiceControlButtonLayout
    ) {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        iconContainer.layoutParams = LayoutParams(ICON_CONTAINER_WIDTH_DP.dp, ICON_CONTAINER_HEIGHT_DP.dp)
        addView(iconContainer)
        if (layout == VoiceControlButtonLayout.PILL) {
            addView(controlLabel(context, title, iconColor))
        }
    }
}
