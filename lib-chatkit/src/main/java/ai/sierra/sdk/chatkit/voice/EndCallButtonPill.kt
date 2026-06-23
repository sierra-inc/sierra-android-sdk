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

public class EndCallButtonPill @JvmOverloads constructor(
    context: Context,
    @ColorInt backgroundColor: Int,
    @ColorInt iconColor: Int,
    @DrawableRes iconResId: Int? = null,
    title: String = "End call",
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        configurePillButton(backgroundColor = backgroundColor, contentDescriptionText = title)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        val iconContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(ICON_CONTAINER_WIDTH_DP.dp, ICON_CONTAINER_HEIGHT_DP.dp)
            addView(
                staticIcon(
                    context,
                    iconResId ?: R.drawable.sierra_chatkit_ic_call_end_24,
                    iconColor,
                    END_ICON_WIDTH_DP,
                    END_ICON_HEIGHT_DP
                )
            )
        }
        addView(iconContainer)
        addView(controlLabel(context, title, iconColor))
    }
}
