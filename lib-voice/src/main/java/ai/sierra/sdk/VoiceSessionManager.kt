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
import android.os.Handler
import android.os.Looper
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

internal interface VoiceSessionDelegate {
    fun onReceiveCredentials(conversationID: String, encryptionKey: String?)
    fun onReceiveAttachments(attachments: List<Map<String, Any?>>)
    fun onReceiveInitialAudio() {}
    fun onStartInitialAudioPlayback() {}
    fun onChangeState(state: VoiceSessionManager.State)
    fun onError(error: Throwable)
    fun onEnd()
    fun onContinueInChat() {}
    fun onReceiveResumeToken(token: String) {}
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
    customizeOkHttpClient: ((OkHttpClient.Builder) -> Unit)? = null,
    private val enableText: Boolean = true,
    private val forwardAgentAttachments: Boolean = true,
    private val delegate: VoiceSessionDelegate
) : SecretRefreshVoiceSession {
    private val conversationId: String = conversationId ?: UUID.randomUUID().toString()
    @Volatile private var resumeToken: String? = resumeToken

    enum class State {
        CONNECTING,
        LISTENING,
        SPEAKING,
        ENDED,
    }

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
    private var hasDeliveredSessionInfo = false
    @Volatile private var hasDeliveredInitialAudioMessage = false
    @Volatile private var hasDeliveredInitialAudioPlayback = false
    @Volatile private var isUserListeningPaused = false
    @Volatile private var isSystemListeningPaused = false
    @Volatile private var isSpeakingMuted = false

    private val okHttpClient = buildVoiceOkHttpClient(customize = customizeOkHttpClient)
    private var webSocket: WebSocket? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<QueuedAudioBuffer>()
    @Volatile private var isPlaying = false

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private val sampleRate = 24000
    private val compatibilityDate = "2026-04-29"

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
                endSessionForExternalAudioInterruption("audio_focus_loss:$focusChange")
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

    private fun endSessionForExternalAudioInterruption(reason: String) {
        if (!isSessionRunning) {
            return
        }
        isSystemListeningPaused = true
        disconnect(rawReason = reason)
        mainHandler.post { delegate.onEnd() }
    }

    fun connect() {
        state = State.CONNECTING
        isSessionRunning = true
        hasDeliveredSessionInfo = false
        hasDeliveredInitialAudioMessage = false
        hasDeliveredInitialAudioPlayback = false

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
        if (!config.headlessAPIToken.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer ${config.headlessAPIToken}")
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasRunning = isSessionRunning
                isSessionRunning = false
                mainHandler.post {
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
                if (!isSessionRunning) {
                    return
                }
                isSessionRunning = false
                mainHandler.post {
                    delegate.onError(t)
                    state = State.ENDED
                }
            }
        })
    }

    fun disconnect(
        sendCloseMessage: Boolean = true,
        closeReason: AgentVoiceCloseReason = AgentVoiceCloseReason.NORMAL
    ) {
        disconnect(sendCloseMessage = sendCloseMessage, rawReason = closeReason.rawValue)
    }

    private fun disconnect(sendCloseMessage: Boolean = true, rawReason: String) {
        isSessionRunning = false
        hasDeliveredSessionInfo = false
        hasDeliveredInitialAudioMessage = false
        hasDeliveredInitialAudioPlayback = false
        isUserListeningPaused = false
        isSystemListeningPaused = false
        isSpeakingMuted = false
        resetSpeakingGateState()
        stopAudio()
        if (sendCloseMessage) {
            sendClose(rawReason)
        }
        webSocket?.close(1000, rawReason)
        webSocket = null
        state = State.ENDED
    }

    fun pauseListening() {
        isUserListeningPaused = true
    }

    fun resumeListening() {
        isUserListeningPaused = false
    }

    fun interrupt() {
        clearAudioQueue()
    }

    fun sendTextClient(text: String) {
        sendJSON(
            JSONObject()
                .put("type", "text_client")
                .put("msgNum", nextMsgNum())
                .put("subMsg", JSONObject().put("text", text))
        )
    }

    override fun sendAttachmentsClient(attachments: List<Map<String, Any?>>) {
        val arr = JSONArray()
        attachments.forEach { arr.put(JSONObject(it)) }
        sendJSON(
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

    private fun sendOpen() {
        val subMsg = JSONObject()
            .put("compatibilityDate", compatibilityDate)
            .put("conversationId", conversationId)
            .put("audioFormat", "linear16")
            .put("locale", localeTag)
            .put("enableText", enableText)
            .put("forwardAgentAttachments", forwardAgentAttachments)
            .put("enableSessionInfo", true)
        if (resumeConversation) {
            subMsg.put("resumeConversation", true)
        }
        resumeReason?.let { reason ->
            subMsg.put("resumeReason", reason.rawValue)
        }
        resumeToken?.let { token ->
            subMsg.put("resumeToken", token)
        }
        if (agentParameters.isNotEmpty()) {
            subMsg.put("agentParameters", JSONObject(agentParameters))
        }
        sendJSON(
            JSONObject()
                .put("type", "open")
                .put("msgNum", nextMsgNum())
                .put("subMsg", subMsg)
        )
    }

    private fun sendAudioClient(audioData: ByteArray) {
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

    private fun sendJSON(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    private fun nextMsgNum(): Int {
        return msgNum.incrementAndGet()
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            Log.w(VOICE_TAG, "Failed to parse SVP message")
            return
        }
        val type = json.optString("type")
        val subMsg = json.optJSONObject("subMsg") ?: JSONObject()
        when (type) {
            "opened" -> {
                val token = if (subMsg.has("resumeToken") && !subMsg.isNull("resumeToken")) {
                    subMsg.optString("resumeToken").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                if (token != null) {
                    resumeToken = token
                    mainHandler.post { delegate.onReceiveResumeToken(token) }
                }
                if (setupAudio()) {
                    mainHandler.post { state = State.LISTENING }
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
            "clear" -> clearAudioQueue()
            "end_conversation" -> {
                // A server-initiated voice->chat handoff ends the call with the continue_in_chat
                // custom reason. Surface it distinctly so the host can continue the same
                // conversation in chat; any other end is a normal session end.
                if (subMsg.optString("customReason") == AgentVoiceCloseReason.CONTINUE_IN_CHAT.rawValue) {
                    sendClose(AgentVoiceCloseReason.CONTINUE_IN_CHAT.rawValue)
                    disconnect(sendCloseMessage = false, closeReason = AgentVoiceCloseReason.CONTINUE_IN_CHAT)
                    mainHandler.post { delegate.onContinueInChat() }
                } else {
                    sendClose(AgentVoiceCloseReason.NORMAL.rawValue)
                    disconnect(sendCloseMessage = false)
                    mainHandler.post { delegate.onEnd() }
                }
            }
            "transfer" -> {
                sendClose(AgentVoiceCloseReason.TRANSFERRED.rawValue)
                disconnect(sendCloseMessage = false, closeReason = AgentVoiceCloseReason.TRANSFERRED)
                mainHandler.post { delegate.onEnd() }
            }
        }
    }

    private fun setupAudio(): Boolean {
        if (audioRecord != null && audioTrack != null) {
            return reactivateAudioIfNeeded()
        }
        if (audioRecord != null || audioTrack != null) {
            stopAudio()
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

        try {
            audioManager = AppContextHolder.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = true
            }

            val focusAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = focusReq
            audioManager?.requestAudioFocus(focusReq)

            audioRecord?.startRecording()
            enableAudioEffects(audioRecord?.audioSessionId ?: 0)
            audioTrack?.play()
            startRecordLoop(minRecordSize)
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

    private fun startRecordLoop(bufferSize: Int) {
        recordThread = thread(start = true, name = "sierra-voice-record") {
            val buffer = ByteArray(bufferSize)
            var wasSpeakingState = false
            while (isSessionRunning) {
                val record = audioRecord ?: break
                if (isUserListeningPaused || isSystemListeningPaused || isSpeakingMuted) {
                    Thread.sleep(10)
                    continue
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }

                val isSpeakingState = state == State.SPEAKING
                if (isSpeakingState != wasSpeakingState) {
                    resetSpeakingGateState()
                    wasSpeakingState = isSpeakingState
                }

                if (isSpeakingState && !disableInterruptions) {
                    val rms = computeRms16(buffer, read)
                    if (!shouldPassSpeakingGate(rms)) {
                        continue
                    }
                } else {
                    resetSpeakingGateState()
                }
                sendAudioClient(buffer.copyOf(read))
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackThread = thread(start = true, name = "sierra-voice-playback") {
            while (isSessionRunning) {
                val queued = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val track = audioTrack ?: continue
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
                track.write(queued.data, 0, queued.data.size)
                if (!queued.mark.isNullOrEmpty()) {
                    sendPlaybackProgress(queued.mark)
                }
                if (playbackQueue.isEmpty()) {
                    isPlaying = false
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
