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
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(secretRefreshAttachment())

        awaitUntil { session.memoryUpdates.size == 1 && session.attachmentBatches.size == 1 }

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
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(secretRefreshAttachment())

        awaitUntil { callbacks.calls.get() == 1 }
        Thread.sleep(50)
        shadowOf(android.os.Looper.getMainLooper()).idle()

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
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(
            secretRefreshAttachment(
                retryConfig = mapOf(
                    "maxAttempts" to 2,
                    "retryDelaySeconds" to 0.01,
                    "maxDelaySeconds" to 0.01,
                )
            )
        )

        awaitUntil { callbacks.calls.get() == 2 }

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
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(secretRefreshAttachment())
        orchestrator.handle(secretRefreshAttachment())

        awaitUntil { callbacks.calls.get() == 1 && pendingReply.get() != null }
        pendingReply.get()?.invoke(SecretExpiryResult.Success("fresh-value"))

        awaitUntil { session.memoryUpdates.size == 1 }

        assertEquals(1, callbacks.calls.get())
        orchestrator.cancel()
    }

    @Test
    fun cancelPreventsPendingReplySideEffects() {
        val session = FakeVoiceSession()
        val pendingReply = AtomicReference<((SecretExpiryResult) -> Unit)?>(null)
        val callbacks = FakeCallbacks { _, reply ->
            pendingReply.set(reply)
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(secretRefreshAttachment())

        awaitUntil { pendingReply.get() != null }
        orchestrator.cancel()

        pendingReply.get()?.invoke(SecretExpiryResult.Success("fresh-value"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(50)

        assertTrue(session.memoryUpdates.isEmpty())
        assertTrue(session.attachmentBatches.isEmpty())
    }

    @Test
    fun cancelCancelsDelayedAttempt() {
        val session = FakeVoiceSession()
        val callbacks = FakeCallbacks { _, reply ->
            reply(SecretExpiryResult.Success("fresh-value"))
        }
        val orchestrator = SecretRefreshOrchestrator(session, callbacks)

        orchestrator.handle(secretRefreshAttachment(initialDelaySeconds = 0.2))
        // Give handle() enough time to run on the worker and register the delayed future.
        Thread.sleep(50)
        orchestrator.cancel()

        Thread.sleep(300)
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

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        error("Condition was not met before timeout")
    }

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
}
