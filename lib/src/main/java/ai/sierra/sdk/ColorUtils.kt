// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Color

@SierraInternalApi
public fun Int.toHexColor(): String {
    val alpha = Color.alpha(this)
    val red = Color.red(this)
    val green = Color.green(this)
    val blue = Color.blue(this)
    return if (alpha == 255) {
        String.format("#%02X%02X%02X", red, green, blue)
    } else {
        String.format("#%02X%02X%02X%02X", red, green, blue, alpha)
    }
}
