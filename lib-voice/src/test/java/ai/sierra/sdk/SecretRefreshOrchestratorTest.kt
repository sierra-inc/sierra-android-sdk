// Copyright Sierra

package ai.sierra.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SecretRefreshOrchestratorTest {
    @Test
    fun successSendsMemoryUpdateAndAck() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Success("fresh-value"))
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, TestScheduler())

        orchestrator.handle(secretRefreshAttachment())
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(mapOf("TOWEL_TOKEN" to "fresh-value"), session.memoryUpdates.single().secrets)
        val ack = session.attachmentBatches.single().single()
        assertEquals("custom", ack["type"])
        val data = ack["data"] as Map<*, *>
        assertEquals("secret_refreshed", data["type"])
        assertEquals("TOWEL_TOKEN", data["secretName"])
        orchestrator.cancel()
    }

    @Test
    fun nullReplyStopsWithoutSending() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Success(null))
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, TestScheduler())

        orchestrator.handle(secretRefreshAttachment())
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, callbacks.calls.get())
        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
        orchestrator.cancel()
    }

    @Test
    fun errorRetriesUntilMaxAttempts() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Error("try again"))
        }
        val scheduler = TestScheduler()
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, scheduler)

        orchestrator.handle(
            secretRefreshAttachment(
                retryConfig = mapOf(
                    "maxAttempts" to 2,
                    "retryDelaySeconds" to 0.01,
                    "maxDelaySeconds" to 0.01,
                )
            )
        )

        shadowOf(android.os.Looper.getMainLooper()).idle()
        scheduler.runScheduledTasks()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(2, callbacks.calls.get())
        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
        orchestrator.cancel()
    }

    @Test
    fun duplicateSecretIsIgnoredWhileRefreshInFlight() {
        val session = FakeVoiceSession()
        val pendingReply = AtomicReference<((SecretExpiryResult) -> Unit)?>(null)
        val callbacks = FakeCallbacks { _, reply ->
            pendingReply.set(reply)
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, TestScheduler())

        orchestrator.handle(secretRefreshAttachment())
        orchestrator.handle(secretRefreshAttachment())

        shadowOf(android.os.Looper.getMainLooper()).idle()
        pendingReply.get()?.invoke(SecretExpiryResult.Success("fresh-value"))

        assertEquals(1, callbacks.calls.get())
        assertEquals(1, session.memoryUpdates.size)
        orchestrator.cancel()
    }

    @Test
    fun cancelPreventsPendingReplySideEffects() {
        val session = FakeVoiceSession()
        val pendingReply = AtomicReference<((SecretExpiryResult) -> Unit)?>(null)
        val callbacks = FakeCallbacks { _, reply ->
            pendingReply.set(reply)
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, TestScheduler())

        orchestrator.handle(secretRefreshAttachment())

        shadowOf(android.os.Looper.getMainLooper()).idle()
        orchestrator.cancel()

        pendingReply.get()?.invoke(SecretExpiryResult.Success("fresh-value"))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
    }

    @Test
    fun cancelPreventsQueuedCallbackInvocation() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Success("fresh-value"))
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, TestScheduler())

        orchestrator.handle(secretRefreshAttachment())
        orchestrator.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, callbacks.calls.get())
        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
    }

    @Test
    fun cancelCancelsDelayedAttempt() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Success("fresh-value"))
        }
        val scheduler = TestScheduler()
        val orchestrator = SecretRefreshOrchestrator(session, callbacks, scheduler)

        orchestrator.handle(secretRefreshAttachment(initialDelaySeconds = 0.2))
        orchestrator.cancel()

        scheduler.runScheduledTasks()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, callbacks.calls.get())
        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
    }

    private fun secretRefreshAttachment(
        initialDelaySeconds: Double = 0.0,
        retryConfig: Map<String, Any?>? = null,
    ): Map<String, Any?> = mapOf(
        "type" to "custom",
        "data" to buildMap<String, Any?> {
            put("type", "secret_refresh")
            put("secretName", "TOWEL_TOKEN")
            put("initialDelaySeconds", initialDelaySeconds)
            if (retryConfig != null) {
                put("retryConfig", retryConfig)
            }
        },
    )

    private data class MemoryUpdate(
        val secrets: Map<String, String>?,
        val variables: Map<String, String>?,
    )

    private class FakeVoiceSession : SecretRefreshVoiceSession {
        val memoryUpdates = mutableListOf<MemoryUpdate>()
        val attachmentBatches = mutableListOf<List<Map<String, Any?>>>()

        override fun sendMemoryUpdateClient(
            secrets: Map<String, String>?,
            variables: Map<String, String>?,
        ) {
            memoryUpdates.add(MemoryUpdate(secrets, variables))
        }

        override fun sendAttachmentsClient(attachments: List<Map<String, Any?>>) {
            attachmentBatches.add(attachments)
        }
    }

    private class FakeCallbacks(
        private val handler: (String, (SecretExpiryResult) -> Unit) -> Unit,
    ) : VoiceCallbacks {
        val calls = AtomicInteger(0)

        override fun onVoiceEnded() {}

        override fun onVoiceError(error: Throwable) {}

        override fun onSecretExpiry(
            secretName: String,
            replyHandler: (SecretExpiryResult) -> Unit,
        ) {
            calls.incrementAndGet()
            handler(secretName, replyHandler)
        }
    }

    private class TestScheduler : SecretRefreshScheduler {
        private val scheduledTasks = mutableListOf<TestScheduledTask>()
        private var shutdown = false

        override fun execute(task: () -> Unit) {
            if (!shutdown) task()
        }

        override fun schedule(
            delayMilliseconds: Long,
            task: () -> Unit,
        ): SecretRefreshScheduledTask {
            val scheduledTask = TestScheduledTask(task)
            scheduledTasks.add(scheduledTask)
            return scheduledTask
        }

        override fun shutdown() {
            shutdown = true
        }

        fun runScheduledTasks() {
            val tasks = scheduledTasks.toList()
            scheduledTasks.clear()
            tasks.forEach { it.run() }
        }

        private class TestScheduledTask(
            private val task: () -> Unit,
        ) : SecretRefreshScheduledTask {
            private var cancelled = false

            override fun cancel() {
                cancelled = true
            }

            fun run() {
                if (!cancelled) task()
            }
        }
    }
}
