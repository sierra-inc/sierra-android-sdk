// Copyright Sierra

package ai.sierra.sdk

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates an agent-initiated secret refresh during a voice call.
 *
 * The agent emits a `secret_refresh` custom attachment over SVP `attachments_server`.
 * AgentVoiceFragment peels the attachment off the inbound batch and forwards it here. The
 * orchestrator calls the host's onSecretExpiry callback, pushes the fresh value into conversation
 * memory with `memory_update_client`, and sends a `secret_refreshed` custom attachment back to the
 * agent so the agent can continue immediately on a synthetic turn.
 */
internal interface SecretRefreshVoiceSession {
    fun sendMemoryUpdateClient(
        secrets: Map<String, String>? = null,
        variables: Map<String, String>? = null,
    )
    fun sendAttachmentsClient(attachments: List<Map<String, Any?>>)
}

internal interface SecretRefreshScheduledTask {
    fun cancel()
}

internal interface SecretRefreshScheduler {
    fun execute(task: () -> Unit)
    fun schedule(delayMilliseconds: Long, task: () -> Unit): SecretRefreshScheduledTask
    fun shutdown()
}

internal class SecretRefreshOrchestrator(
    voiceSession: SecretRefreshVoiceSession,
    callbacks: VoiceCallbacks?,
    private val scheduler: SecretRefreshScheduler = ExecutorSecretRefreshScheduler(),
) {
    private data class RetryConfig(
        val maxAttempts: Int,
        val initialRetryDelaySeconds: Double,
        val maxDelaySeconds: Double,
    ) {
        companion object {
            val DEFAULT = RetryConfig(maxAttempts = 1, initialRetryDelaySeconds = 5.0, maxDelaySeconds = 10.0)
        }
    }

    private data class PendingRefresh(
        val secretName: String,
        val initialDelaySeconds: Double,
        val retryConfig: RetryConfig,
    )

    private val voiceSessionRef = WeakReference(voiceSession)
    // Mutable orchestration state is confined to the scheduler thread.
    private var callbacksRef = WeakReference(callbacks)
    private val inFlightSecretNames = mutableSetOf<String>()
    private val workItems = mutableMapOf<Int, SecretRefreshScheduledTask>()
    private var nextWorkItemID = 0
    private var cancelled = false
    private val cancelRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setCallbacks(callbacks: VoiceCallbacks?) {
        if (cancelRequested.get()) return
        executeOnWorker {
            callbacksRef = WeakReference(callbacks)
        }
    }

    fun cancel() {
        if (!cancelRequested.compareAndSet(false, true)) return
        try {
            scheduler.execute {
                // Shutdown is one-way: cancel scheduled retries and ignore any host reply that
                // arrives after this point. executeOnWorker catches the resulting
                // RejectedExecutionException, so late onSecretExpiry replies are dropped as part
                // of session cleanup.
                cleanupAfterCancel()
                scheduler.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            // Already shutting down; any queued work is either complete or cannot be submitted.
        }
    }

    private fun cleanupAfterCancel() {
        cancelled = true
        for (item in workItems.values) {
            item.cancel()
        }
        workItems.clear()
        inFlightSecretNames.clear()
    }

    fun handle(attachment: Map<String, Any?>) {
        if (cancelRequested.get()) return
        val data = attachment["data"] as? Map<*, *> ?: return
        val secretName = data["secretName"] as? String
        if (secretName.isNullOrEmpty()) {
            Log.w(VOICE_TAG, "SecretRefreshOrchestrator: missing or empty secretName, ignoring")
            return
        }

        val pending = PendingRefresh(
            secretName = secretName,
            initialDelaySeconds = max(0.0, data.number("initialDelaySeconds") ?: 0.0),
            retryConfig = parseRetryConfig(data["retryConfig"] as? Map<*, *>),
        )

        executeOnWorker worker@{
            if (cancelled) return@worker
            if (inFlightSecretNames.contains(secretName)) {
                return@worker
            }
            inFlightSecretNames.add(secretName)
            scheduleAttempt(pending, attemptNumber = 1, delaySeconds = pending.initialDelaySeconds)
        }
    }

    private fun parseRetryConfig(raw: Map<*, *>?): RetryConfig {
        val defaults = RetryConfig.DEFAULT
        if (raw == null) return defaults
        return RetryConfig(
            maxAttempts = max(1, raw.number("maxAttempts")?.toInt() ?: defaults.maxAttempts),
            initialRetryDelaySeconds = max(
                0.0,
                raw.number("retryDelaySeconds") ?: defaults.initialRetryDelaySeconds
            ),
            maxDelaySeconds = max(0.0, raw.number("maxDelaySeconds") ?: defaults.maxDelaySeconds),
        )
    }

    private fun scheduleAttempt(pending: PendingRefresh, attemptNumber: Int, delaySeconds: Double) {
        if (delaySeconds <= 0.0) {
            executeOnWorker {
                attempt(pending, attemptNumber, pending.retryConfig.initialRetryDelaySeconds)
            }
            return
        }
        val workItemID = nextWorkItemID++
        val future = scheduler.schedule(
            (delaySeconds * 1000).toLong(),
        ) {
            workItems.remove(workItemID)
            attempt(pending, attemptNumber, pending.retryConfig.initialRetryDelaySeconds)
        }
        workItems[workItemID] = future
    }

    private fun attempt(pending: PendingRefresh, attemptNumber: Int, retryDelaySeconds: Double) {
        if (cancelRequested.get() || cancelled) return
        val callbacks = callbacksRef.get()
        if (callbacks == null) {
            Log.d(VOICE_TAG, "SecretRefreshOrchestrator: no callbacks registered, dropping refresh")
            inFlightSecretNames.remove(pending.secretName)
            return
        }

        mainHandler.post {
            if (cancelRequested.get()) return@post
            callbacks.onSecretExpiry(pending.secretName) { result ->
                executeOnWorker worker@{
                    if (cancelled) return@worker
                    when (result) {
                        is SecretExpiryResult.Success -> {
                            val value = result.value
                            if (value != null) {
                                applySuccess(pending.secretName, value)
                            } else {
                                Log.d(
                                    VOICE_TAG,
                                    "SecretRefreshOrchestrator: host returned nil value; refresh not supported, stopping"
                                )
                                inFlightSecretNames.remove(pending.secretName)
                            }
                        }
                        is SecretExpiryResult.Error -> {
                            handleFailure(pending, attemptNumber, retryDelaySeconds)
                        }
                    }
                }
            }
        }
    }

    private fun applySuccess(secretName: String, value: String) {
        val voiceSession = voiceSessionRef.get()
        if (voiceSession == null) {
            inFlightSecretNames.remove(secretName)
            return
        }
        voiceSession.sendMemoryUpdateClient(secrets = mapOf(secretName to value))
        val ack = mapOf(
            "type" to ATTACHMENT_TYPE,
            "data" to mapOf(
                "type" to SECRET_REFRESHED_DATA_TYPE,
                "secretName" to secretName,
            )
        )
        voiceSession.sendAttachmentsClient(listOf(ack))
        inFlightSecretNames.remove(secretName)
    }

    private fun handleFailure(
        pending: PendingRefresh,
        attemptNumber: Int,
        retryDelaySeconds: Double,
    ) {
        if (attemptNumber >= pending.retryConfig.maxAttempts) {
            Log.d(VOICE_TAG, "SecretRefreshOrchestrator: giving up after max attempts")
            inFlightSecretNames.remove(pending.secretName)
            return
        }
        val nextDelay = min(retryDelaySeconds * 2, pending.retryConfig.maxDelaySeconds)
        if (retryDelaySeconds <= 0.0) {
            executeOnWorker {
                attempt(pending, attemptNumber + 1, nextDelay)
            }
            return
        }
        val workItemID = nextWorkItemID++
        val future = scheduler.schedule(
            (retryDelaySeconds * 1000).toLong(),
        ) {
            workItems.remove(workItemID)
            attempt(pending, attemptNumber + 1, nextDelay)
        }
        workItems[workItemID] = future
    }

    private fun executeOnWorker(block: () -> Unit) {
        if (cancelRequested.get()) return
        try {
            scheduler.execute(block)
        } catch (_: RejectedExecutionException) {
            // The orchestrator was cancelled between the guard above and task submission.
        }
    }

    private fun Map<*, *>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

    companion object {
        private const val ATTACHMENT_TYPE = "custom"
        private const val SECRET_REFRESH_DATA_TYPE = "secret_refresh"
        private const val SECRET_REFRESHED_DATA_TYPE = "secret_refreshed"

        fun isSecretRefreshAttachment(raw: Map<String, Any?>): Boolean {
            if (raw["type"] != ATTACHMENT_TYPE) return false
            val data = raw["data"] as? Map<*, *> ?: return false
            return data["type"] == SECRET_REFRESH_DATA_TYPE
        }
    }
}

private class ExecutorSecretRefreshScheduler : SecretRefreshScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ai.sierra.SecretRefreshOrchestrator")
    }

    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(
        delayMilliseconds: Long,
        task: () -> Unit,
    ): SecretRefreshScheduledTask {
        val future = executor.schedule(task, delayMilliseconds, TimeUnit.MILLISECONDS)
        return object : SecretRefreshScheduledTask {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    override fun shutdown() {
        executor.shutdown()
    }
}
