// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import ai.sierra.sdk.chatkit.R
import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

public class EndCallButtonLegacy @JvmOverloads constructor(
    context: Context,
    @ColorInt backgroundColor: Int,
    @ColorInt iconColor: Int,
    @DrawableRes iconResId: Int? = null,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    init {
        configureLegacyButton(backgroundColor = backgroundColor, iconColor = iconColor)
        setImageResource(iconResId ?: R.drawable.sierra_chatkit_ic_call_end_24)
        contentDescription = "End call"
    }
}
