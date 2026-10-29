// Copyright Sierra
@file:OptIn(SierraInternalApi::class)

package ai.sierra.sdk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

internal interface VoiceSessionDelegate {
    fun onReceiveCredentials(conversationID: String, encryptionKey: String?)
    fun onReceiveAttachments(attachments: List<Map<String, Any?>>)
    fun onReceiveConversationEvent(event: AgentVoiceConversationEvent) {}
    fun onReceiveInitialAudio() {}
    fun onStartInitialAudioPlayback() {}
    fun onChangeState(state: VoiceSessionManager.State)
    fun onError(error: Throwable)

    /**
     * The voice connection failed and was not recovered. [isAuthenticationFailure] is true when the
     * server rejected the handshake with HTTP 401 or 403.
     */
    fun onConnectionError(error: Throwable, isAuthenticationFailure: Boolean) {
        onError(error)
    }
    fun onEnd()

    /** The connection was lost and the first reconnect attempt is scheduled. */
    fun onStartReconnecting() {}

    /** A reconnect reached `opened` after [onStartReconnecting]. */
    fun onReconnect() {}
    fun onContinueInChat() {}
    fun onReceiveResumeToken(token: String) {}
    fun onMessageSendReady() {}
    fun onUpdateInputAudioLevel(level: Float) {}
    fun onUpdateOutputAudioLevel(level: Float) {}

    /** Per-band microphone levels for the voice waveform, each normalized to `0..1`. */
    fun onUpdateInputAudioBands(bands: FloatArray) {}

    /** Per-band agent-speech levels for the voice waveform, each normalized to `0..1`. */
    fun onUpdateOutputAudioBands(bands: FloatArray) {}
}

public data class AgentVoiceConversationEvent(
    val messageId: String,
    val eventType: String,
    val role: String,
    val text: String = "",
    val attachments: List<Map<String, Any?>> = emptyList()
)

private fun JSONObject.toAgentVoiceConversationEvent(): AgentVoiceConversationEvent? {
    val messageId = optString("messageId").takeIf { it.isNotEmpty() } ?: return null
    val eventType = optString("eventType").takeIf { it.isNotEmpty() } ?: return null
    val role = optString("role").takeIf { it.isNotEmpty() } ?: return null
    val attachments = mutableListOf<Map<String, Any?>>()
    val arr = optJSONArray("attachments")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            attachments.add(jsonObjectToMap(obj))
        }
    }
    return AgentVoiceConversationEvent(
        messageId = messageId,
        eventType = eventType,
        role = role,
        text = optString("text", ""),
        attachments = attachments
    )
}

public enum class AgentVoiceCloseReason(public val rawValue: String) {
    ERROR("error"),
    NORMAL("normal"),
    TRANSFERRED("transferred"),
    CONTINUE_IN_CHAT("continue_in_chat"),
    ;

    // Keep this enum in sync with the SVP ClientCloseReason values.
}

public enum class AgentVoiceResumeReason(public val rawValue: String) {
    CONTINUE_IN_VOICE("continue_in_voice"),
    ;

    // Keep this enum in sync with the SVP ClientResumeReason values.
}

internal class VoiceSessionManager(
    private val config: AgentConfig,
    conversationId: String? = null,
    private val resumeConversation: Boolean = false,
    private val resumeReason: AgentVoiceResumeReason? = null,
    resumeToken: String? = null,
    private val disableInterruptions: Boolean = false,
    private val localeTag: String = Locale.getDefault().toLanguageTag(),
    private val agentParameters: Map<String, String> = emptyMap(),
    private val userIdentityToken: String? = null,
    customizeOkHttpClient: ((OkHttpClient.Builder) -> Unit)? = null,
    private val enableText: Boolean = true,
    private val forwardAgentAttachments: Boolean = true,
    private val enableConversationEvents: Boolean = false,
    delegate: VoiceSessionDelegate
) : SecretRefreshVoiceSession {
    @Volatile
    var delegate: VoiceSessionDelegate = delegate

    private val conversationId: String = conversationId ?: UUID.randomUUID().toString()
    @Volatile private var resumeToken: String? = resumeToken
    // At this SDK's compatibility date the server resumes only when `open` carries a token, so a
    // session without a host token is fresh even when [resumeConversation] is set.
    private val startedAsResume: Boolean = !resumeToken.isNullOrEmpty()

    enum class State {
        CONNECTING,
        LISTENING,
        SPEAKING,
        ENDED,
    }

    val currentState: State
        get() = state

    /**
     * The server rejects `text_client` and `attachments_client` before `opened` by ending the
     * call. The session reaches [State.LISTENING] only after `opened`.
     */
    val isMessageSendReady: Boolean
        get() = state.let { it == State.LISTENING || it == State.SPEAKING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var _state: State = State.CONNECTING
    private var state: State
        get() = synchronized(stateLock) { _state }
        set(value) {
            val changed: Boolean
            synchronized(stateLock) {
                changed = _state != value
                if (changed) {
                    _state = value
                    if (disableInterruptions) {
                        isSpeakingMuted = value == State.SPEAKING
                    }
                }
            }
            if (changed) {
                delegate.onChangeState(value)
            }
        }

    private val msgNum = AtomicInteger(0)
    @Volatile private var isSessionRunning = false
    @Volatile private var hasDeliveredSessionInfo = false
    @Volatile private var hasDeliveredInitialAudioMessage = false
    @Volatile private var hasDeliveredInitialAudioPlayback = false
    @Volatile private var isUserListeningPaused = false
    @Volatile private var isSystemListeningPaused = false
    @Volatile private var isSpeakingMuted = false

    private val okHttpClient = buildVoiceOkHttpClient(customize = customizeOkHttpClient)
    @Volatile private var webSocket: WebSocket? = null

    // Incremented for each new socket and on disconnect, so callbacks from a replaced socket are
    // ignored.
    private val connectionGeneration = AtomicInteger(0)

    // Held while the generation changes and while a socket callback checks it and applies its
    // effects, so a callback from a replaced socket has no effect after the replacement. Never
    // held across audio teardown or delegate calls.
    private val connectionLock = Any()

    // The server ends the session if it receives anything other than `open` before `opened`, so
    // every other outgoing message is dropped until the current socket has received `opened`.
    @Volatile private var isConnectionOpen = false

    // The server keeps the token from the connection that created the conversation and issues an
    // unusable one in `opened` on a resume, so a resumed connection keeps the token it sent.
    @Volatile private var isResumedConnection = false

    // True from a reconnect socket's `open` until it receives a server message other than
    // `opened`. The server reports a reconnect it cannot set up (for example a resume of a
    // conversation it no longer has) with an error `end_conversation` right after `opened`.
    @Volatile private var isAwaitingReconnectTraffic = false

    // Main thread only.
    private var hasDeliveredMessageSendReady = false
    private var reconnectAttempt = 0
    private var connectionOpenedAtMillis: Long? = null
    var isReconnecting = false
        private set
    private val reconnectRunnable = Runnable { reconnect() }
    private var openTimeout: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Set while a lost connection waits for the device to rejoin a network, to the connection
    // generation at the drop, so a network callback after a disconnect is ignored.
    private var networkWaitGeneration: Int? = null

    // When the connection was first lost since the last `opened`. The network wait ends
    // [NETWORK_WAIT_TIMEOUT_MILLIS] after this, however often the network comes and goes.
    private var connectionLostAtMillis: Long? = null
    private val networkWaitDeadline = Runnable {
        if (networkWaitGeneration != connectionGeneration.get() || !isSessionRunning) return@Runnable
        Log.w(VOICE_TAG, "Voice connection lost and no network returned within ${NETWORK_WAIT_TIMEOUT_MILLIS}ms")
        isSessionRunning = false
        failSession(IllegalStateException("No network for voice reconnect"), isAuthenticationFailure = false)
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<QueuedAudioBuffer>()
    @Volatile private var isPlaying = false
    @Volatile private var lastDispatchedInputAudioLevel = 0f
    @Volatile private var lastDispatchedOutputAudioLevel = 0f

    // Suppression paths repeat resting bands every frame, so drop the duplicates the way the scalar
    // level dispatchers above drop repeated zeros. Both start true because nothing has been sent yet.
    @Volatile private var lastDispatchedInputBandsWereResting = true
    @Volatile private var lastDispatchedOutputBandsWereResting = true

    // Each analyser is confined to one audio thread: the record loop owns the input one, the playback
    // loop owns the output one.
    private val inputSpectrumAnalyser = AudioSpectrumAnalyser()
    private val outputSpectrumAnalyser = AudioSpectrumAnalyser()

    // Playback can stop off the playback thread, which owns the output analyser. The thread advances
    // the analyser through silence at display cadence so release matches the Web Audio analyser.
    @Volatile private var shouldReleaseOutputSpectrum = false

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private val sampleRate = 24000
    private val compatibilityDate = "2026-08-27"

    // One read off the recorder, sized to keep analyser updates near the Web SDK's display cadence
    // rather than using the recorder's whole buffer. Everything downstream is driven per read --
    // the frame-counted echo gate below, the mute pill's level, and the waveform's bands -- so the
    // read size, not just the recorder's capacity, sets their cadence.
    private val inputFrameBytes = inputFrameByteCount(sampleRate)

    // Adaptive speaking gate state (mirrors iOS behavior).
    // Accessed from the record thread.
    private val echoGateFloorMultiplier = 2.5f
    private val echoGateFloorDecay = 0.985f
    private val echoGateMinThreshold = 0.015f
    private val echoGateOnsetFrames = 2
    private val echoGateOffsetFrames = 4
    private val echoGateInitialFloorRms = 0.01f
    private var echoGateFloorRms = echoGateInitialFloorRms
    private var echoGateAboveCount = 0
    private var echoGateBelowCount = 0
    private var echoGatePassing = false

    private data class QueuedAudioBuffer(val data: ByteArray, val mark: String?)
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (!isSessionRunning) {
            return@OnAudioFocusChangeListener
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                failSessionForAudioInterruption("audio_focus_loss:$focusChange")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isSystemListeningPaused = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isSystemListeningPaused = false
                if (!isUserListeningPaused) {
                    reactivateAudioIfNeeded()
                }
            }
        }
    }

    private fun failSessionForAudioInterruption(reason: String) {
        // Disconnect first, so that a late end_conversation from the old socket cannot end the
        // session before the user leaves the error screen.
        disconnect(rawReason = reason)
        mainHandler.post { delegate.onError(VoiceAudioException(VoiceAudioException.Reason.INTERRUPTED)) }
    }

    fun connect() {
        state = State.CONNECTING
        isSessionRunning = true
        hasDeliveredSessionInfo = false
        hasDeliveredInitialAudioMessage = false
        hasDeliveredInitialAudioPlayback = false
        hasDeliveredMessageSendReady = false
        reconnectAttempt = 0
        isReconnecting = false
        connectionOpenedAtMillis = null
        networkWaitGeneration = null
        connectionLostAtMillis = null

        // Set up audio before the socket opens so the audio route change does not overlap the start
        // of the connection. Captured audio is dropped until `opened`.
        if (!setupAudio()) {
            isSessionRunning = false
            stopAudio()
            state = State.ENDED
            return
        }
        registerNetworkCallback()
        openWebSocket(isReconnect = false)
    }

    /** Main thread. Reconnects as soon as the device rejoins a network after a drop. */
    private fun registerNetworkCallback() {
        val connectivityManager = AppContextHolder.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    val generation = networkWaitGeneration ?: return@post
                    if (generation != connectionGeneration.get() || !isSessionRunning) return@post
                    networkWaitGeneration = null
                    mainHandler.removeCallbacks(networkWaitDeadline)
                    reconnectAttempt += 1
                    Log.i(VOICE_TAG, "Network available, reconnecting voice (attempt $reconnectAttempt)")
                    reconnect()
                }
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // A host can strip ACCESS_NETWORK_STATE from the merged manifest. Reconnects then use
            // the backoff schedule without waiting for the network.
            Log.w(VOICE_TAG, "Could not watch the network for voice reconnects", e)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        if (networkCallback == null) return true
        val connectivityManager = AppContextHolder.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return true
        return connectivityManager.activeNetwork != null
    }

    /** Main thread. Stops every timer and callback that could reopen the connection. */
    private fun stopConnectionRecovery() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(networkWaitDeadline)
        networkWaitGeneration = null
        cancelOpenTimeout()
        networkCallback?.let { callback ->
            networkCallback = null
            try {
                AppContextHolder.applicationContext
                    .getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(VOICE_TAG, "Could not stop watching the network", e)
            }
        }
    }

    /** Opens a new socket for the running session without resetting per-session state. */
    private fun reconnect() {
        if (!isSessionRunning) {
            return
        }
        openWebSocket(isReconnect = true)
    }

    private fun openWebSocket(isReconnect: Boolean) {
        val generation = synchronized(connectionLock) {
            isConnectionOpen = false
            connectionGeneration.incrementAndGet()
        }
        cancelOpenTimeout()
        // The socket has no read timeout and pings start only once it is open, so a connection that
        // never receives `opened` would otherwise wait forever. A reconnect timeout uses up an
        // attempt. A first-connection timeout is followed by the full reconnect schedule.
        val openTimeoutMillis = if (isReconnect) RECONNECT_OPEN_TIMEOUT_MILLIS else FIRST_CONNECTION_OPEN_TIMEOUT_MILLIS
        val timeout = Runnable {
            openTimeout = null
            handleConnectionLost(
                generation,
                IllegalStateException("Voice connection did not open within ${openTimeoutMillis}ms"),
                handshakeStatusCode = null,
                onlyIfUnopened = true,
            )
        }
        openTimeout = timeout
        mainHandler.postDelayed(timeout, openTimeoutMillis)

        var svpPath = "${config.apiHost.voiceBaseURL}/chat/voice/svp/${config.token}"
        if (!config.target.isNullOrEmpty()) {
            svpPath += "/release/${config.target}"
        }

        val wsURL = svpPath
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        val requestBuilder = Request.Builder()
            .url(wsURL)
            .header("User-Agent", generateVoiceUserAgent(AppContextHolder.applicationContext))
        applySvpAuthentication(requestBuilder, config)

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subMsg = synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) {
                        return
                    }
                    buildOpenSubMessage(isReconnect).also {
                        isResumedConnection = it.has("resumeToken")
                        isAwaitingReconnectTraffic = isReconnect
                    }
                }
                webSocket.send(
                    JSONObject()
                        .put("type", "open")
                        .put("msgNum", nextMsgNum())
                        .put("subMsg", subMsg)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, generation)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            // A close frame means the server ended the session on purpose, so it is not retried.
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Checked on the main thread so an attempt that already timed out there is stale.
                mainHandler.post {
                    if (generation != connectionGeneration.get()) {
                        return@post
                    }
                    val wasOpen = isConnectionOpen
                    isConnectionOpen = false
                    val wasRunning = isSessionRunning
                    isSessionRunning = false
                    // The server closes a connection whose `open` it cannot process, such as a
                    // resume of a conversation it no longer has. Retrying would fail the same way.
                    if (wasRunning && !wasOpen) {
                        failSession(
                            IllegalStateException("Voice connection closed before opened (code=$code, reason=$reason)"),
                            isAuthenticationFailure = false,
                        )
                        return@post
                    }
                    stopConnectionRecovery()
                    // If the server closes before session bootstrap completes (e.g. wrong target),
                    // surface an explicit error instead of silently ending.
                    if (wasRunning && !hasDeliveredSessionInfo) {
                        delegate.onError(
                            IllegalStateException(
                                "Voice session closed before initialization (code=$code, reason=$reason)"
                            )
                        )
                    }
                    state = State.ENDED
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val statusCode = response?.code
                mainHandler.post { handleConnectionLost(generation, t, statusCode) }
            }
        })
    }

    /** Main thread. [onlyIfUnopened] skips a socket that has received `opened`. */
    private fun handleConnectionLost(
        generation: Int,
        error: Throwable,
        handshakeStatusCode: Int?,
        onlyIfUnopened: Boolean = false,
    ) {
        val newGeneration = synchronized(connectionLock) {
            if (generation != connectionGeneration.get() || !isSessionRunning || (onlyIfUnopened && isConnectionOpen)) {
                return
            }
            isConnectionOpen = false
            // Cancelling a socket that is still connecting reports another failure for it; ignore it.
            connectionGeneration.incrementAndGet()
        }
        webSocket?.cancel()
        webSocket = null
        cancelOpenTimeout()
        val now = SystemClock.elapsedRealtime()
        val lostAt = connectionLostAtMillis ?: now.also { connectionLostAtMillis = it }

        val openedAt = connectionOpenedAtMillis
        val wasUnopenedReconnectAttempt = isReconnecting && openedAt == null
        if (openedAt != null && now - openedAt >= STABLE_CONNECTION_MILLIS) {
            reconnectAttempt = 0
        }
        connectionOpenedAtMillis = null

        val isHandshakeRejected = handshakeStatusCode != null && handshakeStatusCode in 400..499
        if (!isHandshakeRejected && !isNetworkAvailable()) {
            // An attempt without a network says nothing about the server, so it is not counted.
            if (wasUnopenedReconnectAttempt) {
                reconnectAttempt = maxOf(0, reconnectAttempt - 1)
            }
            val remainingMillis = lostAt + NETWORK_WAIT_TIMEOUT_MILLIS - now
            if (remainingMillis <= 0) {
                Log.w(VOICE_TAG, "Voice connection lost with no network past the network wait", error)
                isSessionRunning = false
                failSession(error, isAuthenticationFailure = false)
                return
            }
            Log.w(VOICE_TAG, "Voice connection lost with no network, waiting up to ${remainingMillis}ms", error)
            networkWaitGeneration = newGeneration
            mainHandler.removeCallbacks(networkWaitDeadline)
            mainHandler.postDelayed(networkWaitDeadline, remainingMillis)
            mainHandler.removeCallbacks(reconnectRunnable)
            beginReconnecting()
            return
        }
        if (isHandshakeRejected || reconnectAttempt >= RECONNECT_BASE_DELAYS_MILLIS.size) {
            Log.w(VOICE_TAG, "Voice connection lost, not reconnecting (attempts=$reconnectAttempt, status=$handshakeStatusCode)")
            isSessionRunning = false
            failSession(error, isAuthenticationFailure = handshakeStatusCode == 401 || handshakeStatusCode == 403)
            return
        }

        val baseDelayMillis = RECONNECT_BASE_DELAYS_MILLIS[reconnectAttempt]
        val delayMillis = (baseDelayMillis * Random.nextDouble(1 - RECONNECT_JITTER, 1 + RECONNECT_JITTER)).toLong()
        reconnectAttempt += 1
        Log.w(VOICE_TAG, "Voice connection lost, reconnecting in ${delayMillis}ms (attempt $reconnectAttempt)", error)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMillis)
        beginReconnecting()
    }

    /** Main thread. */
    private fun beginReconnecting() {
        state = State.CONNECTING
        // Playback marks from the old connection mean nothing to the new one.
        clearAudioQueue()
        if (!isReconnecting) {
            isReconnecting = true
            delegate.onStartReconnecting()
        }
    }

    /** Main thread. */
    private fun cancelOpenTimeout() {
        openTimeout?.let { mainHandler.removeCallbacks(it) }
        openTimeout = null
    }

    /** Main thread. Ends a session that can no longer continue and reports why. */
    private fun failSession(error: Throwable, isAuthenticationFailure: Boolean) {
        stopConnectionRecovery()
        isReconnecting = false
        stopAudio()
        delegate.onConnectionError(error, isAuthenticationFailure)
        state = State.ENDED
    }

    fun disconnect(
        sendCloseMessage: Boolean = true,
        closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL
    ) {
        disconnect(sendCloseMessage = sendCloseMessage, rawReason = closeReason.rawValue)
    }

    private fun disconnect(sendCloseMessage: Boolean = true, rawReason: String) {
        isSessionRunning = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.post { stopConnectionRecovery() }
        hasDeliveredSessionInfo = false
        hasDeliveredInitialAudioMessage = false
        hasDeliveredInitialAudioPlayback = false
        isUserListeningPaused = false
        isSystemListeningPaused = false
        isSpeakingMuted = false
        resetSpeakingGateState()
        // The audio threads own the analysers, so only the reported levels are zeroed from here.
        dispatchInputAudioLevel(0f)
        dispatchOutputAudioLevel(0f)
        dispatchInputAudioBands(AudioSpectrumAnalyser.restingLevels())
        dispatchOutputAudioBands(AudioSpectrumAnalyser.restingLevels())
        stopAudio()
        if (sendCloseMessage) {
            sendClose(rawReason)
        }
        synchronized(connectionLock) {
            isConnectionOpen = false
            connectionGeneration.incrementAndGet()
        }
        webSocket?.close(1000, rawReason)
        webSocket = null
        state = State.ENDED
    }

    fun pauseListening() {
        isUserListeningPaused = true
        dispatchInputAudioLevel(0f)
        dispatchInputAudioBands(AudioSpectrumAnalyser.restingLevels())
    }

    fun resumeListening() {
        isUserListeningPaused = false
    }

    fun interrupt() {
        clearAudioQueue()
    }

    fun sendTextClient(text: String): Boolean {
        return sendJSON(
            JSONObject()
                .put("type", "text_client")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("text", text))
        )
    }

    override fun sendAttachmentsClient(attachments: List<Map<String, Any?>>): Boolean {
        val arr = JSONArray()
        attachments.forEach { arr.put(JSONObject(it)) }
        return sendJSON(
            JSONObject()
                .put("type", "attachments_client")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("attachments", arr))
        )
    }

    override fun sendMemoryUpdateClient(
        secrets: Map<String, String>?,
        variables: Map<String, String>?,
    ) {
        val subMsg = JSONObject()
        if (!secrets.isNullOrEmpty()) {
            subMsg.put("secrets", JSONObject(secrets))
        }
        if (!variables.isNullOrEmpty()) {
            subMsg.put("variables", JSONObject(variables))
        }
        if (subMsg.length() == 0) {
            Log.d(VOICE_TAG, "SVP send: memory_update_client skipped (no variables or secrets)")
            return
        }

        sendJSON(
            JSONObject()
                .put("type", "memory_update_client")
                .put("msgNum", nextMsgNum())
                .put("subMsg", subMsg)
        )
    }

    internal fun buildOpenSubMessage(isReconnect: Boolean = false): JSONObject {
        val subMsg = JSONObject()
            .put("compatibilityDate", compatibilityDate)
            .put("conversationId", conversationId)
            .put("audioFormat", "linear16")
            .put("locale", localeTag)
            .put("enableText", enableText)
            .put("forwardAgentAttachments", forwardAgentAttachments)
            .put("enableConversationEvents", enableConversationEvents)
            .put("enableSessionInfo", true)
        if (resumeConversation) {
            subMsg.put("resumeConversation", true)
        }
        // After `session_info` a reconnect is a plain network resume; resending the reason would
        // make the server repeat its handling (for example `continue-in-voice`).
        if (!hasDeliveredSessionInfo) {
            resumeReason?.let { reason ->
                subMsg.put("resumeReason", reason.rawValue)
            }
        }
        val token = resumeToken
        if (token != null && (!isReconnect || shouldResumeOnReconnect(hasDeliveredSessionInfo, startedAsResume))) {
            subMsg.put("resumeToken", token)
        }
        if (agentParameters.isNotEmpty()) {
            subMsg.put("agentParameters", JSONObject(agentParameters))
        }
        if (!userIdentityToken.isNullOrEmpty()) {
            subMsg.put("userIdentityToken", userIdentityToken)
        }
        return subMsg
    }

    private fun sendAudioClient(audioData: ByteArray) {
        // Capture starts before the socket opens; skip encoding and logging until it has.
        if (!isConnectionOpen) {
            return
        }
        val base64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
        sendJSON(
            JSONObject()
                .put("type", "audio_client")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("audioData", base64))
        )
    }

    private fun sendPlaybackProgress(mark: String) {
        sendJSON(
            JSONObject()
                .put("type", "playback_progress")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("mark", mark))
        )
    }

    private fun sendClose(reason: String) {
        sendJSON(
            JSONObject()
                .put("type", "close")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("reason", reason))
        )
    }

    private fun sendJSON(payload: JSONObject): Boolean {
        val socket = webSocket ?: return false
        if (!isConnectionOpen) {
            Log.d(VOICE_TAG, "SVP send: ${payload.optString("type")} dropped before opened")
            return false
        }
        return socket.send(payload.toString())
    }

    private fun dispatchInputAudioLevel(level: Float) {
        if (level == 0f && lastDispatchedInputAudioLevel == 0f) {
            return
        }
        lastDispatchedInputAudioLevel = level
        mainHandler.post { delegate.onUpdateInputAudioLevel(level) }
    }

    private fun dispatchOutputAudioLevel(level: Float) {
        if (level == 0f && lastDispatchedOutputAudioLevel == 0f) {
            return
        }
        lastDispatchedOutputAudioLevel = level
        mainHandler.post { delegate.onUpdateOutputAudioLevel(level) }
    }

    private fun dispatchInputAudioBands(bands: FloatArray) {
        val resting = bands.all { it == 0f }
        if (resting && lastDispatchedInputBandsWereResting) {
            return
        }
        lastDispatchedInputBandsWereResting = resting
        mainHandler.post { delegate.onUpdateInputAudioBands(bands) }
    }

    private fun dispatchOutputAudioBands(bands: FloatArray) {
        val resting = bands.all { it == 0f }
        if (resting && lastDispatchedOutputBandsWereResting) {
            return
        }
        lastDispatchedOutputBandsWereResting = resting
        mainHandler.post { delegate.onUpdateOutputAudioBands(bands) }
    }

    /** Reports silence while preserving analyser history, matching Web Audio's muted read path. */
    private fun dispatchSuppressedInputLevels() {
        dispatchInputAudioLevel(0f)
        dispatchInputAudioBands(AudioSpectrumAnalyser.restingLevels())
    }

    private fun nextMsgNum(): Int {
        return msgNum.incrementAndGet()
    }

    /** [generation] is the connection generation of the socket that received [text]. */
    internal fun handleMessage(text: String, generation: Int = connectionGeneration.get()) {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            Log.w(VOICE_TAG, "Failed to parse SVP message")
            return
        }
        val type = json.optString("type")
        val subMsg = json.optJSONObject("subMsg") ?: JSONObject()
        // Socket replacement holds the same lock, so a message from a replaced socket has no effect.
        // Ending the session waits for the lock to be released, because it joins the audio threads.
        // Stopping the session under the lock keeps a reconnect from starting in between.
        val endSession = synchronized(connectionLock) {
            if (generation != connectionGeneration.get()) return
            applyMessage(type, subMsg, generation)?.also { isSessionRunning = false }
        }
        endSession?.invoke()
    }

    /** Called with [connectionLock] held. Returns the work that ends the session, if any. */
    private fun applyMessage(type: String, subMsg: JSONObject, generation: Int): (() -> Unit)? {
        val isRejectedReconnect = isAwaitingReconnectTraffic && type == "end_conversation" &&
            subMsg.optString("reason") == SERVER_END_REASON_ERROR
        if (type != "opened") {
            isAwaitingReconnectTraffic = false
        }
        when (type) {
            "opened" -> {
                val token = if (subMsg.has("resumeToken") && !subMsg.isNull("resumeToken")) {
                    subMsg.optString("resumeToken").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                if (token != null && !isResumedConnection) {
                    resumeToken = token
                    mainHandler.post { delegate.onReceiveResumeToken(token) }
                }
                isConnectionOpen = true
                mainHandler.post {
                    if (!isSessionRunning || generation != connectionGeneration.get()) return@post
                    cancelOpenTimeout()
                    connectionLostAtMillis = null
                    connectionOpenedAtMillis = SystemClock.elapsedRealtime()
                    state = State.LISTENING
                    if (isReconnecting) {
                        isReconnecting = false
                        delegate.onReconnect()
                    }
                    if (!hasDeliveredMessageSendReady) {
                        hasDeliveredMessageSendReady = true
                        delegate.onMessageSendReady()
                    }
                }
            }
            "session_info" -> {
                val convId = subMsg.optString("conversationId")
                val key = subMsg.optString("encryptionKey").takeIf { it.isNotEmpty() }
                if (!hasDeliveredSessionInfo && convId.isNotEmpty() && key != null) {
                    hasDeliveredSessionInfo = true
                    mainHandler.post { delegate.onReceiveCredentials(convId, key) }
                }
            }
            "audio_server" -> {
                val audioDataB64 = subMsg.optString("audioData")
                if (audioDataB64.isNotEmpty()) {
                    val data = Base64.decode(audioDataB64, Base64.DEFAULT)
                    val mark = if (subMsg.has("mark") && !subMsg.isNull("mark")) {
                        subMsg.optString("mark")
                    } else {
                        null
                    }
                    if (!hasDeliveredInitialAudioMessage) {
                        hasDeliveredInitialAudioMessage = true
                        mainHandler.post { delegate.onReceiveInitialAudio() }
                    }
                    enqueueAudio(data, mark)
                }
            }
            "attachments_server" -> {
                val attachments = mutableListOf<Map<String, Any?>>()
                val arr = subMsg.optJSONArray("attachments")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        attachments.add(jsonObjectToMap(obj))
                    }
                }
                mainHandler.post { delegate.onReceiveAttachments(attachments) }
            }
            "conversation_event_server" -> {
                if (!enableConversationEvents) {
                    return null
                }
                val event = subMsg.toAgentVoiceConversationEvent()
                if (event == null) {
                    Log.w(VOICE_TAG, "SVP conversation_event_server received but could not parse subMsg")
                    return null
                }
                mainHandler.post { delegate.onReceiveConversationEvent(event) }
            }
            "clear" -> clearAudioQueue()
            "end_conversation" -> {
                // A server-initiated voice->chat handoff ends the call with the continue_in_chat
                // custom reason. Surface it distinctly so the host can continue the same
                // conversation in chat; any other end is a normal session end.
                return if (isRejectedReconnect) {
                    {
                        Log.w(VOICE_TAG, "Voice reconnect rejected by the server")
                        delegate.onConnectionError(
                            IllegalStateException("Voice reconnect rejected by the server"),
                            isAuthenticationFailure = false,
                        )
                        disconnect(closeReason = AgentVoiceCloseReason.ERROR)
                    }
                } else if (subMsg.optString("customReason") == AgentVoiceCloseReason.CONTINUE_IN_CHAT.rawValue) {
                    {
                        sendClose(AgentVoiceCloseReason.CONTINUE_IN_CHAT.rawValue)
                        disconnect(sendCloseMessage = false, closeReason = AgentVoiceCloseReason.CONTINUE_IN_CHAT)
                        mainHandler.post { delegate.onContinueInChat() }
                    }
                } else {
                    {
                        sendClose(AgentVoiceCloseReason.NORMAL.rawValue)
                        disconnect(sendCloseMessage = false)
                        mainHandler.post { delegate.onEnd() }
                    }
                }
            }
            "transfer" -> return {
                sendClose(AgentVoiceCloseReason.TRANSFERRED.rawValue)
                disconnect(sendCloseMessage = false, closeReason = AgentVoiceCloseReason.TRANSFERRED)
                mainHandler.post { delegate.onEnd() }
            }
        }
        return null
    }

    private fun setupAudio(): Boolean {
        if (audioRecord != null && audioTrack != null) {
            return reactivateAudioIfNeeded()
        }
        if (audioRecord != null || audioTrack != null) {
            stopAudio()
        }

        // Allocation throws on some devices (for example AudioTrack.Builder.build()), and this
        // runs from the host's main thread, so every step reports a voice error instead.
        try {
            val manager = AppContextHolder.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            if (manager.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw VoiceAudioException(VoiceAudioException.Reason.UNAVAILABLE)
            }
            // Retain audio ownership only after focus is granted, so refused-start cleanup leaves
            // the existing call's audio mode and route unchanged.
            audioManager = manager
            audioFocusRequest = focusReq
            manager.apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = true
            }

            val minRecordSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(9600)

            val minTrackSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(9600)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecordSize
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minTrackSize)
                .build()

            audioRecord?.startRecording()
            enableAudioEffects(audioRecord?.audioSessionId ?: 0)
            audioTrack?.play()
            startRecordLoop()
            startPlaybackLoop()
            return true
        } catch (e: Throwable) {
            mainHandler.post { delegate.onError(e) }
            return false
        }
    }

    private fun reactivateAudioIfNeeded(): Boolean {
        return try {
            val manager = audioManager ?: AppContextHolder.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = manager
            manager?.mode = AudioManager.MODE_IN_COMMUNICATION
            manager?.let { currentManager ->
                @Suppress("DEPRECATION")
                run {
                    currentManager.isSpeakerphoneOn = true
                }
            }
            audioFocusRequest?.let { request -> manager?.requestAudioFocus(request) }

            val currentRecord = audioRecord
            val currentTrack = audioTrack
            if (currentRecord == null || currentTrack == null) {
                setupAudio()
            } else {
                if (currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    currentRecord.startRecording()
                }
                if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    currentTrack.play()
                }
                true
            }
        } catch (e: Throwable) {
            mainHandler.post { delegate.onError(e) }
            false
        }
    }

    private fun enableAudioEffects(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        }
    }

    private fun startRecordLoop() {
        recordThread = thread(start = true, name = "sierra-voice-record") {
            inputSpectrumAnalyser.reset()
            val buffer = ByteArray(inputFrameBytes)
            var wasSpeakingState = false
            while (isSessionRunning) {
                val record = audioRecord ?: break
                val policy = capturePolicy(
                    systemPaused = isSystemListeningPaused,
                    userMuted = isUserListeningPaused,
                    speakingMuted = isSpeakingMuted,
                )
                if (policy == CapturePolicy.DROP) {
                    // System / audio-focus-loss pause: the mic isn't reliably delivering and the
                    // session is suspended, so keep dropping (no read) -- distinct from user mute.
                    // See CH-633.
                    dispatchSuppressedInputLevels()
                    Thread.sleep(10)
                    continue
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }

                if (policy == CapturePolicy.SILENCE) {
                    // User mute / speaking-mute: emit equal-length silence instead of dropping so
                    // the server's byte-counted AudioIn clock keeps advancing and agent audio stays
                    // aligned during playback. The real mic bytes are never transmitted while muted.
                    // See CH-633.
                    resetSpeakingGateState()
                    wasSpeakingState = state == State.SPEAKING
                    dispatchSuppressedInputLevels()
                    sendAudioClient(ByteArray(read))
                    continue
                }

                val isSpeakingState = state == State.SPEAKING
                if (isSpeakingState != wasSpeakingState) {
                    resetSpeakingGateState()
                    wasSpeakingState = isSpeakingState
                }

                val rms = computeRms16(buffer, read)
                val passesSpeakingGate = if (isSpeakingState && !disableInterruptions) {
                    shouldPassSpeakingGate(rms)
                } else {
                    resetSpeakingGateState()
                    true
                }
                // When the echo gate is closed, emit equal-length silence instead of dropping the
                // frames so the server's reconstructed AudioIn timeline (built by concatenating
                // frames, with no per-frame timing) keeps its true duration and agent audio stays
                // aligned during playback. See CH-633.
                if (passesSpeakingGate) {
                    dispatchInputAudioLevel(rms)
                    dispatchInputAudioBands(inputSpectrumAnalyser.analyse(buffer, read))
                } else {
                    dispatchSuppressedInputLevels()
                }
                sendAudioClient(gatedTransportFrame(passesSpeakingGate, buffer, read))
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackThread = thread(start = true, name = "sierra-voice-playback") {
            outputSpectrumAnalyser.reset()
            shouldReleaseOutputSpectrum = false
            while (isSessionRunning) {
                val queued = playbackQueue.poll(WAVEFORM_FRAME_MILLIS, TimeUnit.MILLISECONDS)
                if (queued == null) {
                    if (shouldReleaseOutputSpectrum) {
                        val bands = outputSpectrumAnalyser.analyseSilence()
                        dispatchOutputAudioBands(bands)
                        shouldReleaseOutputSpectrum = bands.any { it > 0f }
                    }
                    continue
                }
                val track = audioTrack ?: continue
                shouldReleaseOutputSpectrum = false
                isPlaying = true
                mainHandler.post {
                    if (!hasDeliveredInitialAudioPlayback) {
                        hasDeliveredInitialAudioPlayback = true
                        delegate.onStartInitialAudioPlayback()
                    }
                    if (state == State.LISTENING) {
                        state = State.SPEAKING
                    }
                }
                dispatchOutputAudioLevel(computeRms16(queued.data, queued.data.size))
                dispatchOutputAudioBands(outputSpectrumAnalyser.analyse(queued.data, queued.data.size))
                track.write(queued.data, 0, queued.data.size)
                if (!queued.mark.isNullOrEmpty()) {
                    sendPlaybackProgress(queued.mark)
                }
                if (playbackQueue.isEmpty()) {
                    isPlaying = false
                    shouldReleaseOutputSpectrum = true
                    dispatchOutputAudioLevel(0f)
                    mainHandler.post {
                        if (state == State.SPEAKING) {
                            state = State.LISTENING
                        }
                    }
                }
            }
        }
    }

    private fun enqueueAudio(data: ByteArray, mark: String?) {
        playbackQueue.offer(QueuedAudioBuffer(data, mark))
        if (!isPlaying) {
            mainHandler.post {
                if (state == State.LISTENING) {
                    state = State.SPEAKING
                }
            }
        }
    }

    private fun clearAudioQueue() {
        playbackQueue.clear()
        isPlaying = false
        shouldReleaseOutputSpectrum = true
        dispatchOutputAudioLevel(0f)
        mainHandler.post {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (_: Throwable) {
            }
            if (state == State.SPEAKING) {
                state = State.LISTENING
            }
        }
    }

    private fun stopAudio() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }

        recordThread?.let { thread ->
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
            }
        }
        recordThread = null

        playbackThread?.let { thread ->
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
            }
        }
        playbackThread = null

        acousticEchoCanceler?.release()
        noiseSuppressor?.release()
        automaticGainControl?.release()
        acousticEchoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        playbackQueue.clear()
        isPlaying = false
        shouldReleaseOutputSpectrum = false
        lastDispatchedInputAudioLevel = 0f
        lastDispatchedOutputAudioLevel = 0f
        resetSpeakingGateState()
        audioFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
        }
        audioFocusRequest = null
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager = null
    }

    private fun shouldPassSpeakingGate(rms: Float): Boolean {
        val adaptiveThreshold = max(echoGateMinThreshold, echoGateFloorRms * echoGateFloorMultiplier)

        if (!echoGatePassing) {
            echoGateFloorRms = echoGateFloorDecay * echoGateFloorRms + (1f - echoGateFloorDecay) * rms
        }

        if (rms >= adaptiveThreshold) {
            echoGateAboveCount += 1
            echoGateBelowCount = 0
            if (echoGateAboveCount >= echoGateOnsetFrames) {
                echoGatePassing = true
            }
        } else {
            echoGateAboveCount = 0
            echoGateBelowCount += 1
            if (echoGateBelowCount >= echoGateOffsetFrames) {
                echoGatePassing = false
            }
        }

        return echoGatePassing
    }

    private fun resetSpeakingGateState() {
        echoGatePassing = false
        echoGateAboveCount = 0
        echoGateBelowCount = 0
        echoGateFloorRms = echoGateInitialFloorRms
    }
}

/** The device refused audio focus or took it away, so the voice session cannot continue. */
internal class VoiceAudioException(val reason: Reason) :
    Exception(
        when (reason) {
            Reason.UNAVAILABLE -> "Voice audio unavailable: audio focus request failed"
            Reason.INTERRUPTED -> "Voice audio interrupted: audio focus lost"
        }
    ) {
    enum class Reason { UNAVAILABLE, INTERRUPTED }
}

/** SVP `end_conversation` reason for a server-side failure. */
private const val SERVER_END_REASON_ERROR = "error"

/**
 * A connection must stay open this long after `opened` for its loss to reset the attempt count, so
 * a socket that drops right after each `opened` still runs out of attempts.
 */
private const val STABLE_CONNECTION_MILLIS = 10_000L

/**
 * Delay before each reconnect attempt after a lost connection; its size is the number of attempts.
 * The budget resets when a connection that stayed open for [STABLE_CONNECTION_MILLIS] is lost.
 */
private val RECONNECT_BASE_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000)

/** A reconnect attempt that has not received `opened` within this long fails and counts as used. */
private const val RECONNECT_OPEN_TIMEOUT_MILLIS = 5_000L

/** A first connection that has not received `opened` within this long fails, and the full reconnect schedule follows. */
private const val FIRST_CONNECTION_OPEN_TIMEOUT_MILLIS = 10_000L

/**
 * After the connection is first lost, the session waits at most this long for the device to rejoin a
 * network. Attempts made without a network do not count toward [RECONNECT_BASE_DELAYS_MILLIS].
 */
private const val NETWORK_WAIT_TIMEOUT_MILLIS = 20_000L

/** Each reconnect delay varies by up to this fraction either way. */
private const val RECONNECT_JITTER = 0.2

/**
 * Whether a reconnect resumes the conversation with the resume token or opens it fresh. Before
 * `session_info` the server has no conversation to resume, unless the session itself started as a
 * resume; resuming then fails with "conversation not found for resume".
 */
internal fun shouldResumeOnReconnect(hasDeliveredSessionInfo: Boolean, startedAsResume: Boolean): Boolean =
    hasDeliveredSessionInfo || startedAsResume

internal fun applySvpAuthentication(
    requestBuilder: Request.Builder,
    config: AgentConfig
) {
    val oauthAccessToken = config.oauthAccessToken
    @Suppress("DEPRECATION")
    val headlessAPIToken = config.headlessAPIToken
    if (!oauthAccessToken.isNullOrEmpty()) {
        requestBuilder.header("Authorization", "Bearer $oauthAccessToken")
        requestBuilder.header("X-Sierra-Token-Version", "2")
    } else if (!headlessAPIToken.isNullOrEmpty()) {
        Log.w(VOICE_TAG, "headlessAPIToken is deprecated. If you use a Headless API token, fetch it from your backend at runtime instead of shipping it in the app binary. Prefer oauthAccessToken.")
        requestBuilder.header("Authorization", "Bearer $headlessAPIToken")
    }
}

/**
 * Frame to forward upstream for one captured buffer. When the echo gate passes, this is the
 * captured audio; when it is closed, equal-length silence so the upstream AudioIn stream keeps its
 * true duration (see CH-633). [source] may be a reused capture buffer, so the passing case copies.
 */
internal fun gatedTransportFrame(passesSpeakingGate: Boolean, source: ByteArray, length: Int): ByteArray =
    if (passesSpeakingGate) source.copyOf(length) else ByteArray(length)

/** How the record loop treats one captured buffer while capture is being suppressed. */
internal enum class CapturePolicy {
    /** Emit nothing (system / audio-focus-loss pause: the session is suspended, mic unreliable). */
    DROP,

    /** Emit equal-length silence (user mute / speaking-mute: the call timeline keeps advancing). */
    SILENCE,

    /** Forward captured audio through the normal (echo-gated) path. */
    CAPTURE,
}

/**
 * Decides how a captured buffer is handled while capture is suppressed. System pause wins and drops,
 * because audio-focus loss suspends the session and the mic isn't reliably delivering; user mute and
 * speaking-mute emit equal-length silence so the server's byte-counted AudioIn clock keeps advancing
 * and agent audio stays aligned during playback. See CH-633.
 */
internal fun capturePolicy(systemPaused: Boolean, userMuted: Boolean, speakingMuted: Boolean): CapturePolicy =
    when {
        systemPaused -> CapturePolicy.DROP
        userMuted || speakingMuted -> CapturePolicy.SILENCE
        else -> CapturePolicy.CAPTURE
    }

private const val MILLIS_PER_SECOND = 1000
private const val BYTES_PER_LINEAR16_SAMPLE = 2

/** Duration of one microphone read, giving the waveform 50 input updates per second. */
internal const val INPUT_FRAME_MILLIS = 20

/** Approximate `requestAnimationFrame` cadence used while Web Audio releases into silence. */
private const val WAVEFORM_FRAME_MILLIS = 16L

internal fun inputFrameByteCount(sampleRate: Int): Int =
    sampleRate * INPUT_FRAME_MILLIS / MILLIS_PER_SECOND * BYTES_PER_LINEAR16_SAMPLE

private fun computeRms16(bytes: ByteArray, length: Int): Float {
    if (length < 2) {
        return 0f
    }
    var sum = 0.0
    var i = 0
    val sampleCount = length / 2
    while (i + 1 < length) {
        val lo = bytes[i].toInt() and 0xFF
        val hi = bytes[i + 1].toInt()
        val sample = (hi shl 8) or lo
        val normalized = sample / 32768.0
        sum += normalized * normalized
        i += 2
    }
    return sqrt(sum / sampleCount).toFloat()
}
