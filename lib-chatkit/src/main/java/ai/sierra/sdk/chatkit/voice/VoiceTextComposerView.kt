// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import ai.sierra.sdk.chatkit.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

public class VoiceTextComposerView @JvmOverloads constructor(
    context: Context,
    placeholder: String = "Type a reply",
    @ColorInt backgroundColor: Int = Color.WHITE,
    @ColorInt borderColor: Int = Color.argb(0x1A, 0x11, 0x11, 0x11),
    @ColorInt textColor: Int = Color.rgb(17, 17, 17),
    @ColorInt sendButtonTintColor: Int = Color.rgb(18, 48, 76),
    @DrawableRes sendIconResId: Int = R.drawable.sierra_chatkit_ic_send_arrow_24,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    public val editText: EditText = EditText(context)
    public val sendButton: ImageButton = ImageButton(context)
    public var onSend: (() -> Unit)? = null
    private var sendButtonAnimationGeneration = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 38.dp
        setPadding(14.dp, 0, 8.dp, 0)
        isClickable = true
        isFocusable = true
        background = roundedBackground(backgroundColor, 18.dp.toFloat(), borderColor, 1.dp)
        setOnClickListener {
            if (editText.isEnabled) {
                editText.requestFocus()
            }
        }

        editText.apply {
            setBackgroundColor(Color.TRANSPARENT)
            hint = placeholder
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT
            isSingleLine = false
            minLines = 1
            maxLines = 4
            setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            includeFontPadding = false
            setPadding(0, 0, 8.dp, 0)
            setOnFocusChangeListener { _, _ -> updateSendButtonVisibility() }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSend?.invoke()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateSendButtonVisibility()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        addView(
            editText,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        sendButton.apply {
            setImageResource(sendIconResId)
            imageTintList = ColorStateList.valueOf(sendButtonTintColor)
            background = null
            contentDescription = "Send"
            visibility = GONE
            alpha = 0f
            setPadding(0, 0, 0, 0)
            setOnClickListener { onSend?.invoke() }
        }
        addView(sendButton, LayoutParams(24.dp, 24.dp))
    }

    public fun updateSendButtonVisibility(animated: Boolean = true) {
        val shouldShow = editText.hasFocus() && editText.text?.trim()?.isNotEmpty() == true
        if (sendButton.visibility == VISIBLE && sendButton.alpha == 1f && shouldShow) {
            return
        }
        if (sendButton.visibility != VISIBLE && !shouldShow) {
            return
        }

        val generation = ++sendButtonAnimationGeneration
        sendButton.animate().cancel()
        if (shouldShow) {
            sendButton.visibility = VISIBLE
        }
        val targetAlpha = if (shouldShow) 1f else 0f
        if (animated) {
            sendButton.animate()
                .alpha(targetAlpha)
                .setDuration(150)
                .withEndAction {
                    if (generation == sendButtonAnimationGeneration) {
                        sendButton.visibility = if (shouldShow) VISIBLE else GONE
                    }
                }
                .start()
        } else {
            sendButton.alpha = targetAlpha
            sendButton.visibility = if (shouldShow) VISIBLE else GONE
        }
    }
}

private fun roundedBackground(
    @ColorInt color: Int,
    radius: Float,
    @ColorInt borderColor: Int,
    borderWidth: Int
): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        setStroke(borderWidth, borderColor)
    }
