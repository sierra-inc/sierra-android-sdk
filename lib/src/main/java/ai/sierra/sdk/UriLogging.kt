// Copyright Sierra

package ai.sierra.sdk

import android.net.Uri

@SierraInternalApi
public fun Uri.logSafeDescription(): String {
    return buildString {
        scheme?.let {
            append(it)
            append("://")
        }
        append(host ?: "<unknown-host>")
    }
}
