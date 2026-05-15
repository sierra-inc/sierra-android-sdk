// Copyright Sierra

package ai.sierra.sdk

import android.webkit.WebSettings

/**
 * Applies Sierra's standard WebView hardening: disables file/content URI access, blocks
 * cross-origin reads from `file://` origins, and forces secure mixed-content handling.
 *
 * All Sierra SDK WebViews load remote HTTPS pages from `*.sierra.chat` (or the dev host),
 * so none of them legitimately need `file://` or `content://` access. Setting these
 * defenses explicitly silences SAST flags (CWE-693, "Protection Mechanism Failure")
 * regardless of the host application's `targetSdkVersion`, since several of these
 * settings have different platform defaults across API levels.
 */
@SierraInternalApi
public fun WebSettings.applySierraSecurityDefaults() {
    allowFileAccess = false
    // Google deprecated these in API 30 because the platform defaults already match
    // what we want, but scanners (and OWASP MASVS) still expect explicit assignments
    // so a static analysis pass that doesn't reason about API levels sees the defense.
    @Suppress("DEPRECATION")
    allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    allowUniversalAccessFromFileURLs = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}
