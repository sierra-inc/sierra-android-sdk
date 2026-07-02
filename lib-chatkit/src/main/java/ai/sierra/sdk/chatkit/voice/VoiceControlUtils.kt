// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

internal const val PILL_WIDTH_DP = 168.5f
internal const val PILL_HEIGHT_DP = 48
internal const val COMPACT_PILL_SIZE_DP = 48
internal const val PILL_CONTENT_PADDING_DP = 20
internal const val COMPACT_PILL_CONTENT_PADDING_DP = 12
internal const val PILL_CONTENT_GAP_DP = 6
internal const val ICON_CONTAINER_WIDTH_DP = 32
internal const val ICON_CONTAINER_HEIGHT_DP = 33
internal const val MUTE_ICON_WIDTH_DP = 20
internal const val MUTE_ICON_HEIGHT_DP = 20
internal const val END_ICON_WIDTH_DP = 24
internal const val END_ICON_HEIGHT_DP = 24
internal const val LEGACY_BUTTON_SIZE_DP = 64
internal val UNMUTE_COLOR: Int = Color.rgb(242, 75, 39)

internal fun LinearLayout.configurePillButton(
    @ColorInt backgroundColor: Int,
    contentDescriptionText: String,
    layout: VoiceControlButtonLayout = VoiceControlButtonLayout.PILL
) {
    val width = if (layout == VoiceControlButtonLayout.COMPACT) COMPACT_PILL_SIZE_DP.dp else PILL_WIDTH_DP.dp
    val height = if (layout == VoiceControlButtonLayout.COMPACT) COMPACT_PILL_SIZE_DP.dp else PILL_HEIGHT_DP.dp
    minimumWidth = width
    minimumHeight = height
    val horizontalPadding =
        if (layout == VoiceControlButtonLayout.COMPACT) COMPACT_PILL_CONTENT_PADDING_DP else PILL_CONTENT_PADDING_DP
    setPadding(horizontalPadding.dp, 0, horizontalPadding.dp, 0)
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply {
        setSize(PILL_CONTENT_GAP_DP.dp, 1)
        setColor(Color.TRANSPARENT)
    }
    background = roundedBackground(backgroundColor, height / 2f)
    isClickable = true
    isFocusable = true
    contentDescription = contentDescriptionText
    layoutParams = LinearLayout.LayoutParams(width, height)
}

internal fun ImageView.configureLegacyButton(@ColorInt backgroundColor: Int, @ColorInt iconColor: Int) {
    background = roundedBackground(backgroundColor, LEGACY_BUTTON_SIZE_DP.dp / 2f)
    setColorFilter(iconColor)
    val inset = ((LEGACY_BUTTON_SIZE_DP - 32) / 2).dp
    setPadding(inset, inset, inset, inset)
    scaleType = ImageView.ScaleType.FIT_CENTER
    isClickable = true
    isFocusable = true
    layoutParams = LinearLayout.LayoutParams(LEGACY_BUTTON_SIZE_DP.dp, LEGACY_BUTTON_SIZE_DP.dp)
}

internal fun controlLabel(context: Context, title: String, @ColorInt color: Int): TextView =
    TextView(context).apply {
        text = title
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        includeFontPadding = false
        gravity = Gravity.CENTER
        letterSpacing = -0.02f
        minLines = 1
        maxLines = 1
    }

internal fun staticIcon(
    context: Context,
    @DrawableRes iconResId: Int,
    @ColorInt iconColor: Int,
    widthDp: Int,
    heightDp: Int
): ImageView =
    ImageView(context).apply {
        setImageResource(iconResId)
        setColorFilter(iconColor)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = false
        isFocusable = false
        layoutParams = FrameLayout.LayoutParams(widthDp.dp, heightDp.dp, Gravity.CENTER)
    }

internal val Float.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

internal val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

private fun roundedBackground(@ColorInt color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }
