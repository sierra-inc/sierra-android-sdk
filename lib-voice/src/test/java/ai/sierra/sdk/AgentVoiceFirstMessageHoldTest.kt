// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import ai.sierra.sdk.chatkit.voice.VoiceWaveformView
import android.Manifest
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        AgentVoiceFirstMessageHoldTest.ShadowWebViewFeature::class,
        AgentVoiceFirstMessageHoldTest.ShadowWebViewCompat::class
    ]
)
class AgentVoiceFirstMessageHoldTest {
    private val dnsGate = CountDownLatch(1)
    private var fragment: AgentVoiceFragment? = null

    @After
    fun tearDown() {
        dnsGate.countDown()
        fragment?.voiceSession?.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun holdsThroughGreetingFallbackAndPlaybackUntilFirstCardRenders() {
        val fragment = launchFragment()
        fragment.onChangeState(VoiceSessionManager.State.LISTENING)
        idleFor(Duration.ofSeconds(3))
        fragment.onStartInitialAudioPlayback()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        fragment.onAttachmentRendered(cardRendered = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertLoadingHeld(fragment)

        fragment.onAttachmentRendered(cardRendered = true)

        assertFalse(fragment.spinner().isShown)
        assertFalse(fragment.waveform().isShown)
        assertTrue(fragment.renderer().isShown)
    }

    @Test
    fun timeoutWithoutAttachmentShowsWaveform() {
        val fragment = launchFragment()
        fragment.onChangeState(VoiceSessionManager.State.LISTENING)
        idleFor(Duration.ofSeconds(11))

        assertLoadingHeld(fragment)

        idleFor(Duration.ofSeconds(2))

        assertFalse(fragment.spinner().isShown)
        assertTrue(fragment.waveform().isShown)
        assertEquals(View.GONE, fragment.renderer().visibility)
        assertFalse((fragment.spinner().parent as View).tapCenter())
    }

    @Test
    fun greetingPlaybackShortensHold() {
        val fragment = launchFragment()
        fragment.onChangeState(VoiceSessionManager.State.LISTENING)
        idleFor(Duration.ofSeconds(1))
        fragment.onStartInitialAudioPlayback()
        idleFor(Duration.ofMillis(4_900))

        assertLoadingHeld(fragment)

        idleFor(Duration.ofMillis(200))

        assertFalse(fragment.spinner().isShown)
        assertTrue(fragment.waveform().isShown)
    }

    @Test
    fun fullscreenRequestEndsHold() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        fragment.onDisplayModeChanged(VoiceRendererDisplayMode.FULLSCREEN)

        assertRendererRevealed(fragment)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, fragment.renderer().importantForAccessibility)
    }

    @Test
    fun timeoutAfterAttachmentRevealsRenderer() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        idleFor(Duration.ofSeconds(13))

        assertFalse(fragment.spinner().isShown)
        assertTrue(fragment.renderer().isShown)
    }

    @Test
    fun coverKeepsTouchesFromRendererUntilReveal() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        val rendererTouches = mutableListOf<Int>()
        fragment.renderer().setOnTouchListener { _, event -> rendererTouches.add(event.actionMasked) }
        val content = fragment.renderer().parent as View

        assertTrue(content.tapCenter())
        assertEquals(emptyList<Int>(), rendererTouches)

        fragment.onAttachmentRendered(cardRendered = true)
        content.tapCenter()

        assertEquals(listOf(MotionEvent.ACTION_DOWN), rendererTouches)
    }

    @Test
    fun cardBeforeGreetingRevealsRenderer() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        fragment.onAttachmentRendered(cardRendered = true)

        assertRendererRevealed(fragment)
    }

    @Test
    fun firstReportFromRendererWithoutCardFlagEndsHold() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        fragment.onAttachmentRendered(cardRendered = null)

        assertRendererRevealed(fragment)
    }

    @Test
    fun sessionEndEndsHold() {
        val fragment = launchFragment()
        fragment.onChangeState(VoiceSessionManager.State.ENDED)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(fragment.spinner().isShown)
        assertEquals(View.GONE, fragment.renderer().visibility)
    }

    @Test
    fun sessionErrorEndsHold() {
        val fragment = launchFragment()
        fragment.onError(IllegalStateException("connection lost"))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(fragment.spinner().isShown)
        assertEquals(View.GONE, fragment.renderer().visibility)
    }

    @Test
    fun rendererErrorEndsHold() {
        val fragment = launchFragment()
        fragment.onReceiveAttachments(listOf(CARD_ATTACHMENT))
        fragment.onVoiceRendererError(IllegalStateException("renderer failed"))
        fragment.onStartInitialAudioPlayback()

        assertEquals(View.GONE, fragment.renderer().visibility)
        assertFalse(fragment.spinner().isShown)
        assertTrue(fragment.waveform().isShown)
    }

    private fun assertLoadingHeld(fragment: AgentVoiceFragment) {
        assertTrue(fragment.spinner().isShown)
        assertFalse(fragment.waveform().isShown)
        val renderer = fragment.renderer()
        // Laid out under the loading indicator, not hidden, so the page can draw its first card.
        assertEquals(View.VISIBLE, renderer.visibility)
        assertEquals(0, (renderer.parent as ViewGroup).indexOfChild(renderer))
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, renderer.descendantFocusability)
    }

    private fun assertRendererRevealed(fragment: AgentVoiceFragment) {
        assertFalse(fragment.spinner().isShown)
        assertFalse(fragment.waveform().isShown)
        assertTrue(fragment.renderer().isShown)
        assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, fragment.renderer().descendantFocusability)
    }

    private fun View.tapCenter(): Boolean {
        assertTrue(width > 0 && height > 0)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, width / 2f, height / 2f, 0)
        return dispatchTouchEvent(down).also { down.recycle() }
    }

    private fun launchFragment(): AgentVoiceFragment {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val options = AgentVoiceControllerOptions(
            name = "Test Voice Agent",
            holdLoadingUntilFirstMessageRenders = true
        ).apply {
            voiceOkHttpClientCustomizer = { builder ->
                builder.dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            dnsGate.await()
                            return emptyList()
                        }
                    }
                )
            }
        }
        val created = AgentVoiceController(Agent(AgentConfig(token = "test-token")), options)
            .createFragment() as AgentVoiceFragment
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, created)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        fragment = created
        return created
    }

    private fun idleFor(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    private fun AgentVoiceFragment.spinner(): ProgressBar = requireView().requireDescendant()

    private fun AgentVoiceFragment.waveform(): VoiceWaveformView = requireView().requireDescendant()

    private fun AgentVoiceFragment.renderer(): VoiceRendererView = requireView().requireDescendant()

    private inline fun <reified T : View> View.requireDescendant(): T =
        collectDescendants(this, T::class.java).single()

    private fun <T : View> collectDescendants(root: View, clazz: Class<T>): List<T> {
        val matches = mutableListOf<T>()
        if (root::class.java == clazz) {
            @Suppress("UNCHECKED_CAST")
            matches.add(root as T)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                matches.addAll(collectDescendants(root.getChildAt(index), clazz))
            }
        }
        return matches
    }

    // Robolectric's WebView has no message-listener support, so without these the renderer fails at
    // creation and the hold ends before the test can observe it.
    @Implements(WebViewFeature::class)
    class ShadowWebViewFeature {
        companion object {
            @JvmStatic
            @Implementation
            fun isFeatureSupported(feature: String): Boolean = true
        }
    }

    @Implements(WebViewCompat::class)
    class ShadowWebViewCompat {
        companion object {
            @JvmStatic
            @Implementation
            fun addWebMessageListener(
                webView: WebView,
                jsObjectName: String,
                allowedOriginRules: Set<String>,
                listener: WebViewCompat.WebMessageListener
            ) = Unit

            @JvmStatic
            @Implementation
            fun removeWebMessageListener(webView: WebView, jsObjectName: String) = Unit
        }
    }

    private companion object {
        val CARD_ATTACHMENT = mapOf("type" to "custom", "data" to mapOf("id" to "card-1"))
    }
}
