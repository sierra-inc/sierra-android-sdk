// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.content.Context
import android.graphics.Color
import android.os.Build
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal const val VOICE_TAG = "AgentVoiceController"

internal object AppContextHolder {
    lateinit var applicationContext: Context
}

internal fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        map[key] = when (value) {
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

internal fun jsonArrayToList(arr: JSONArray): List<Any?> {
    val out = mutableListOf<Any?>()
    for (i in 0 until arr.length()) {
        val value = arr.opt(i)
        out.add(
            when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            }
        )
    }
    return out
}

internal fun Int.toHexColor(): String {
    val a = Color.alpha(this)
    val r = Color.red(this)
    val g = Color.green(this)
    val b = Color.blue(this)
    return if (a == 255) {
        String.format("#%02X%02X%02X", r, g, b)
    } else {
        String.format("#%02X%02X%02X%02X", r, g, b, a)
    }
}

internal fun buildVoiceOkHttpClient(
    customize: ((OkHttpClient.Builder) -> Unit)? = null,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
    customize?.invoke(builder)
    return builder.build()
}

internal fun generateVoiceUserAgent(context: Context, isWebView: Boolean = false): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion = packageInfo.versionName ?: "0"
    val appName = context.packageName
    val androidVersion = Build.VERSION.RELEASE
    val model = Build.MODEL
    val suffix = if (isWebView) " WebView" else ""
    return "Sierra-Android-SDK ($appName/$appVersion $model/$androidVersion)$suffix"
}

internal val AgentConfig.conversationRendererURL: String
    get() = "${apiHost.embedBaseURL}/agent/${token}/mobile-renderer"

internal val AgentAPIHost.voiceBaseURL: String
    get() = when (this) {
        AgentAPIHost.LOCAL -> "https://sierra.codes:8084"
        else -> apiBaseURL
    }
