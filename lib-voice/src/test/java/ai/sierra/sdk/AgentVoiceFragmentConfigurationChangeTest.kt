// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import ai.sierra.sdk.chatkit.voice.EndCallButtonPill
import ai.sierra.sdk.chatkit.voice.MuteButtonPill
import ai.sierra.sdk.chatkit.voice.UnmuteButtonPill
import android.Manifest
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class AgentVoiceFragmentConfigurationChangeTest {
    private val dnsGate = CountDownLatch(1)
    private var activeController: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        dnsGate.countDown()
        activeController?.get()?.supportFragmentManager?.fragments
            ?.filterIsInstance<AgentVoiceFragment>()
            ?.forEach { it.voiceSession?.disconnect() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun standaloneConfigurationChangeKeepsVoiceSessionAlive() {
        val dismissCount = AtomicInteger()
        val voiceController = createVoiceController(
            callbacks = object : VoiceCallbacks {
                override fun onVoiceEnded() = Unit

                override fun onVoiceDismissed() {
                    dismissCount.incrementAndGet()
                }

                override fun onVoiceError(error: Throwable) = Unit
            }
        )

        assertSessionSurvivesConfigurationChange(voiceController)
        assertEquals(0, dismissCount.get())
    }

    @Test
    fun coordinatorConfigurationChangeKeepsVoiceSessionAlive() {
        val options = voiceOptions()
        val coordinator = AgentVoiceChatCoordinator(
            agent = Agent(AgentConfig(token = "test-token")),
            options = AgentVoiceChatCoordinator.Options(
                voiceOptions = options,
                chatOptions = AgentChatControllerOptions(name = "Test Chat Agent"),
                canSwitchToChat = false
            )
        )

        assertSessionSurvivesConfigurationChange(coordinator.makeVoiceController())
    }

    @Test
    fun muteStateSurvivesConfigurationChange() {
        val (activityController, fragment) = launchFragment(createVoiceController())
        fragment.requireView().requireDescendant<MuteButtonPill>().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        activityController.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val recreated = currentFragment(activityController)
        assertEquals(View.GONE, recreated.requireView().requireDescendant<MuteButtonPill>().visibility)
        assertEquals(View.VISIBLE, recreated.requireView().requireDescendant<UnmuteButtonPill>().visibility)
    }

    @Test
    fun normalDestroyDisconnectsVoiceSessionAndDismissesOnce() {
        val dismissCount = AtomicInteger()
        val voiceController = createVoiceController(
            callbacks = object : VoiceCallbacks {
                override fun onVoiceEnded() = Unit

                override fun onVoiceDismissed() {
                    dismissCount.incrementAndGet()
                }

                override fun onVoiceError(error: Throwable) = Unit
            }
        )
        val (activityController, fragment) = launchFragment(voiceController)
        val session = fragment.voiceSession
        assertNotNull(session)

        activityController.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VoiceSessionManager.State.ENDED, session?.currentState)
        assertEquals(1, dismissCount.get())
    }

    @Test
    fun explicitEndBeforeDestroyEndsOnceWithoutDismissal() {
        val endCount = AtomicInteger()
        val dismissCount = AtomicInteger()
        val voiceController = createVoiceController(
            callbacks = object : VoiceCallbacks {
                override fun onVoiceEnded() {
                    endCount.incrementAndGet()
                }

                override fun onVoiceDismissed() {
                    dismissCount.incrementAndGet()
                }

                override fun onVoiceError(error: Throwable) = Unit
            }
        )
        val (activityController, fragment) = launchFragment(voiceController)

        fragment.requireView().requireDescendant<EndCallButtonPill>().performClick()
        activityController.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, endCount.get())
        assertEquals(0, dismissCount.get())
    }

    @Test
    fun publicEndDuringConfigurationChangeEndsParkedSessionWithoutRestarting() {
        val endCount = AtomicInteger()
        val dismissCount = AtomicInteger()
        val voiceController = createVoiceController(
            callbacks = object : VoiceCallbacks {
                override fun onVoiceEnded() {
                    endCount.incrementAndGet()
                }

                override fun onVoiceDismissed() {
                    dismissCount.incrementAndGet()
                }

                override fun onVoiceError(error: Throwable) = Unit
            }
        )
        val (activityController, fragment) = launchFragment(voiceController)
        val session = fragment.voiceSession
        assertNotNull(session)
        activityController.get().supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewDestroyed(fragmentManager: FragmentManager, destroyed: Fragment) {
                    if (destroyed === fragment) {
                        voiceController.endConversation()
                    }
                }
            },
            false
        )

        activityController.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val recreated = currentFragment(activityController)
        assertNotSame(fragment, recreated)
        assertEquals(VoiceSessionManager.State.ENDED, session?.currentState)
        assertNull(recreated.voiceSession)
        assertNull(ViewModelProvider(recreated)[AgentVoiceViewModel::class.java].retainedSession)
        assertEquals(1, endCount.get())
        assertEquals(0, dismissCount.get())
    }

    @Test
    fun unclaimedRetainedSessionUsesItsDiscardCloseReasonOnViewModelClear() {
        AppContextHolder.applicationContext = RuntimeEnvironment.getApplication()
        val session = VoiceSessionManager(
            config = AgentConfig(token = "test-token"),
            delegate = NoOpVoiceSessionDelegate()
        )
        var closeMessage: String? = null
        val webSocket = object : WebSocket {
            override fun request(): Request = Request.Builder().url("https://example.test").build()
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean {
                closeMessage = text
                return true
            }
            override fun send(bytes: ByteString): Boolean = true
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() = Unit
        }
        VoiceSessionManager::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true
            set(session, webSocket)
        }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            ViewModelProvider.NewInstanceFactory()
        )[AgentVoiceViewModel::class.java]
        viewModel.retainedSession = RetainedVoiceSession(
            session = session,
            secretRefreshOrchestrator = null,
            isMuted = false,
            latestInputAudioLevel = 0f,
            latestOutputAudioLevel = 0f,
            hasReceivedInitialGreeting = false,
            hasReceivedInitialAudioMessage = false,
            discardCloseReason = AgentVoiceCloseReason.CONTINUE_IN_CHAT
        )

        store.clear()

        assertEquals(VoiceSessionManager.State.ENDED, session.currentState)
        assertNull(viewModel.retainedSession)
        assertEquals(
            AgentVoiceCloseReason.CONTINUE_IN_CHAT.rawValue,
            JSONObject(closeMessage!!).getJSONObject("subMsg").getString("reason")
        )
    }

    private fun assertSessionSurvivesConfigurationChange(voiceController: AgentVoiceController) {
        val (activityController, fragment) = launchFragment(voiceController)
        val session = fragment.voiceSession
        assertNotNull(session)

        activityController.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val recreated = currentFragment(activityController)
        assertNotSame(fragment, recreated)
        assertSame(session, recreated.voiceSession)
        assertSame(recreated, session?.delegate)
        assertNotEquals(VoiceSessionManager.State.ENDED, session?.currentState)
        assertNull(ViewModelProvider(recreated)[AgentVoiceViewModel::class.java].retainedSession)
    }

    private fun createVoiceController(callbacks: VoiceCallbacks? = null): AgentVoiceController {
        return AgentVoiceController(
            agent = Agent(AgentConfig(token = "test-token")),
            options = voiceOptions()
        ).also { it.voiceCallbacks = callbacks }
    }

    private fun voiceOptions(): AgentVoiceControllerOptions {
        return AgentVoiceControllerOptions(name = "Test Voice Agent").apply {
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
    }

    private fun launchFragment(
        voiceController: AgentVoiceController
    ): Pair<ActivityController<FragmentActivity>, AgentVoiceFragment> {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        activeController = activityController
        val fragment = voiceController.createFragment() as AgentVoiceFragment
        activityController.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return activityController to fragment
    }

    private fun currentFragment(
        activityController: ActivityController<FragmentActivity>
    ): AgentVoiceFragment =
        activityController.get().supportFragmentManager.fragments.single() as AgentVoiceFragment

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

    private class NoOpVoiceSessionDelegate : VoiceSessionDelegate {
        override fun onReceiveCredentials(conversationID: String, encryptionKey: String?) = Unit
        override fun onReceiveAttachments(attachments: List<Map<String, Any?>>) = Unit
        override fun onChangeState(state: VoiceSessionManager.State) = Unit
        override fun onError(error: Throwable) = Unit
        override fun onEnd() = Unit
    }
}
