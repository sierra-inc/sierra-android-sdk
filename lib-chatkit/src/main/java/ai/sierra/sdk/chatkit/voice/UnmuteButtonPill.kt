// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import ai.sierra.sdk.chatkit.R
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

public class UnmuteButtonPill @JvmOverloads constructor(
    context: Context,
    @ColorInt backgroundColor: Int,
    @DrawableRes unmuteIconResId: Int? = null,
    title: String = "Mute",
    layout: VoiceControlButtonLayout = VoiceControlButtonLayout.PILL,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        configurePillButton(backgroundColor = backgroundColor, contentDescriptionText = title, layout = layout)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        val iconContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(ICON_CONTAINER_WIDTH_DP.dp, ICON_CONTAINER_HEIGHT_DP.dp)
            addView(
                staticIcon(
                    context,
                    unmuteIconResId ?: R.drawable.sierra_chatkit_ic_mic_off_24,
                    UNMUTE_COLOR,
                    MUTE_ICON_WIDTH_DP,
                    MUTE_ICON_HEIGHT_DP
                )
            )
        }
        addView(iconContainer)
        if (layout == VoiceControlButtonLayout.PILL) {
            addView(controlLabel(context, title, UNMUTE_COLOR))
        }
    }
}
