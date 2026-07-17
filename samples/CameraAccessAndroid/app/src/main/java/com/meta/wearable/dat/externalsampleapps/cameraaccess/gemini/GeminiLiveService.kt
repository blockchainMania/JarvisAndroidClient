package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCallCancellation
import java.io.ByteArrayOutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

sealed class GeminiConnectionState {
    data object Disconnected : GeminiConnectionState()
    data object Connecting : GeminiConnectionState()
    data object SettingUp : GeminiConnectionState()
    data object Ready : GeminiConnectionState()
    data class Error(val message: String) : GeminiConnectionState()
}

class GeminiLiveService {
    companion object {
        private const val TAG = "GeminiLiveService"
    }

    private val _connectionState = MutableStateFlow<GeminiConnectionState>(GeminiConnectionState.Disconnected)
    val connectionState: StateFlow<GeminiConnectionState> = _connectionState.asStateFlow()

    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onInputTranscription: ((String) -> Unit)? = null
    var onOutputTranscription: ((String) -> Unit)? = null
    var onToolCall: ((GeminiToolCall) -> Unit)? = null
    var onToolCallCancellation: ((GeminiToolCallCancellation) -> Unit)? = null

    // Latency tracking
    private var lastUserSpeechEnd: Long = 0
    private var responseLatencyLogged = false

    private var webSocket: WebSocket? = null
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private var connectCallback: ((Boolean) -> Unit)? = null
    private var timeoutTimer: Timer? = null
    private var requireWakeWordForSession: Boolean = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Bumped from 10s: a long continuous TTS burst (e.g. reading back a full business
        // card) tripped "sent ping but didn't receive pong" mid-sentence in testing, killing
        // the connection and cutting the audio off. A longer interval gives a busy connection
        // more slack before OkHttp decides it's dead.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun connect(
        requireWakeWord: Boolean,
        speechContextHint: String? = null,
        callback: (Boolean) -> Unit,
    ) {
        val url = GeminiConfig.websocketURL()
        if (url == null) {
            _connectionState.value = GeminiConnectionState.Error("No API key configured")
            callback(false)
            return
        }

        requireWakeWordForSession = requireWakeWord
        _connectionState.value = GeminiConnectionState.Connecting
        connectCallback = callback

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                _connectionState.value = GeminiConnectionState.SettingUp
                sendSetupMessage(speechContextHint)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = friendlyError(t.message ?: "Unknown error")
                Log.e(TAG, "WebSocket failure: $msg")
                _connectionState.value = GeminiConnectionState.Error(msg)
                _isModelSpeaking.value = false
                resolveConnect(false)
                onDisconnected?.invoke(msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                _connectionState.value = GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
                resolveConnect(false)
                onDisconnected?.invoke(friendlyError("Connection closed (code $code: $reason)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
            }
        })

        // Timeout after 15 seconds (use Timer so we don't block sendExecutor)
        timeoutTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (_connectionState.value == GeminiConnectionState.Connecting
                        || _connectionState.value == GeminiConnectionState.SettingUp) {
                        Log.e(TAG, "Connection timed out")
                        _connectionState.value = GeminiConnectionState.Error("Connection timed out")
                        resolveConnect(false)
                    }
                }
            }, 15000)
        }
    }

    fun disconnect() {
        timeoutTimer?.cancel()
        timeoutTimer = null
        webSocket?.close(1000, null)
        webSocket = null
        onToolCall = null
        onToolCallCancellation = null
        _connectionState.value = GeminiConnectionState.Disconnected
        _isModelSpeaking.value = false
        resolveConnect(false)
    }

    fun sendAudio(data: ByteArray) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64)
                    })
                })
            }
            webSocket?.send(json.toString())
        }
    }

    fun sendVideoFrame(bitmap: Bitmap) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, GeminiConfig.VIDEO_JPEG_QUALITY, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            sendVideoFrameBase64(base64)
        }
    }

    fun sendVideoFrameBase64(base64: String) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64)
                    })
                })
            }
            webSocket?.send(json.toString())
        }
    }

    fun sendToolResponse(response: JSONObject) {
        sendExecutor.execute {
            webSocket?.send(response.toString())
        }
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val json = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turnComplete", true)
                })
            }
            webSocket?.send(json.toString())
        }
    }

    /**
     * Sends an image and text as parts of the SAME user turn, instead of pushing the
     * image over realtimeInput and the text over clientContent as two separate
     * messages. realtimeInput is a background stream with no ordering guarantee
     * relative to clientContent turnComplete -- the model can start answering a
     * turnComplete message before a realtimeInput image has actually been folded
     * into its context, which is what caused vision questions to hallucinate even
     * right after attaching a "fresh" frame. Bundling both into one turn removes
     * that race entirely.
     */
    fun sendTextMessageWithImage(text: String, imageBase64: String) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val json = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", text)
                            })
                        })
                    }))
                    put("turnComplete", true)
                })
            }
            webSocket?.send(json.toString())
        }
    }

    // Private

    private fun resolveConnect(success: Boolean) {
        val cb = connectCallback
        connectCallback = null  // null out BEFORE invoking to prevent re-entrancy
        timeoutTimer?.cancel()
        timeoutTimer = null
        cb?.invoke(success)
    }

    private fun sendSetupMessage(speechContextHint: String?) {
        val systemInstruction = buildString {
            append(GeminiConfig.systemInstruction(requireWakeWordForSession))
            append("\n\n[음성 인식 보정]\n")
            append("- 사용자는 기본적으로 한국어로 말합니다. 영어/아랍어/일본어처럼 들려도 한국어 발화로 우선 해석하세요.\n")
            append("- 한국 사람 이름, 회사명, 직급은 한국어 고유명사로 복원하세요. 예: 'kim yoon seop'처럼 들리면 '김윤섭'으로 해석하세요.\n")
            append("- 연락처 검색, 전화, 문자 요청에서는 아래 연락처 이름 후보를 우선 고려하세요.\n")
            speechContextHint?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
        }
        val config = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", GeminiConfig.MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemInstruction)
                    }))
                })
                // No "tools" here on purpose -- Live never decides which function to call
                // anymore (GeminiRootAgentClient does, via a stateless Flash request). Live
                // is pure TTS: it only speaks the text it's handed. See
                // JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2.
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("silenceDurationMs", 2500)
                        put("prefixPaddingMs", 80)
                    })
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                    put("turnCoverage", "TURN_INCLUDES_ALL_INPUT")
                })
                put("contextWindowCompression", JSONObject().apply {
                    put("slidingWindow", JSONObject().apply {
                        put("targetTokens", 80000)
                    })
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        // Send directly (not via sendExecutor) to ensure it's the first message
        val sent = webSocket?.send(config.toString()) ?: false
        Log.d(TAG, "Live config sent=$sent model=${GeminiConfig.MODEL}")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // The API historically returned setupComplete; newer docs use config terminology.
            if (json.has("setupComplete") || json.has("configComplete")) {
                _connectionState.value = GeminiConnectionState.Ready
                resolveConnect(true)
                return
            }

            if (json.has("error")) {
                val error = json.optJSONObject("error")
                val message = friendlyError(error?.optString("message")
                    ?.ifBlank { null }
                    ?: json.optString("error", "Gemini Live configuration failed")
                )
                Log.e(TAG, "Gemini Live error: $message")
                _connectionState.value = GeminiConnectionState.Error(message)
                resolveConnect(false)
                onDisconnected?.invoke(message)
                return
            }

            // GoAway
            if (json.has("goAway")) {
                val goAway = json.getJSONObject("goAway")
                val seconds = goAway.optJSONObject("timeLeft")?.optInt("seconds", 0) ?: 0
                _connectionState.value = GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
                onDisconnected?.invoke("Server closing (time left: ${seconds}s)")
                return
            }

            // Tool call
            val toolCall = GeminiToolCall.fromJSON(json)
            if (toolCall != null) {
                Log.d(TAG, "Tool call received: ${toolCall.functionCalls.size} function(s)")
                onToolCall?.invoke(toolCall)
                return
            }

            // Tool call cancellation
            val cancellation = GeminiToolCallCancellation.fromJSON(json)
            if (cancellation != null) {
                Log.d(TAG, "Tool call cancellation: ${cancellation.ids.joinToString()}")
                onToolCallCancellation?.invoke(cancellation)
                return
            }

            // Server content
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    _isModelSpeaking.value = false
                    onInterrupted?.invoke()
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                if (mimeType.startsWith("audio/pcm")) {
                                    val base64Data = inlineData.optString("data", "")
                                    if (base64Data.isNotEmpty()) {
                                        val audioData = Base64.decode(base64Data, Base64.DEFAULT)
                                        if (!_isModelSpeaking.value) {
                                            _isModelSpeaking.value = true
                                            if (lastUserSpeechEnd > 0 && !responseLatencyLogged) {
                                                val latency = System.currentTimeMillis() - lastUserSpeechEnd
                                                Log.d(TAG, "[Latency] ${latency}ms (user speech end -> first audio)")
                                                responseLatencyLogged = true
                                            }
                                        }
                                        onAudioReceived?.invoke(audioData)
                                    }
                                }
                            } else if (part.has("text")) {
                                Log.d(TAG, part.getString("text"))
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _isModelSpeaking.value = false
                    responseLatencyLogged = false
                    onTurnComplete?.invoke()
                }

                if (serverContent.has("inputTranscription")) {
                    val transcription = serverContent.getJSONObject("inputTranscription")
                    val transcriptText = transcription.optString("text", "")
                    if (transcriptText.isNotEmpty()) {
                        Log.d(TAG, "You: $transcriptText")
                        lastUserSpeechEnd = System.currentTimeMillis()
                        responseLatencyLogged = false
                        onInputTranscription?.invoke(transcriptText)
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val transcription = serverContent.getJSONObject("outputTranscription")
                    val transcriptText = transcription.optString("text", "")
                    if (transcriptText.isNotEmpty()) {
                        Log.d(TAG, "AI: $transcriptText")
                        onOutputTranscription?.invoke(transcriptText)
                    }
                }
                return
            }

            Log.d(TAG, "Unhandled Gemini Live message: ${text.take(500)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun friendlyError(raw: String): String {
        val normalized = raw.lowercase()
        return when {
            "prepayment credits are depleted" in normalized ->
                "Gemini API 크레딧이 소진되었습니다. Google AI Studio 프로젝트의 Billing/Credits를 충전해주세요."
            "api key not valid" in normalized || "invalid api key" in normalized ->
                "Gemini API 키가 유효하지 않습니다. Settings 또는 Secrets.kt의 키를 확인해주세요."
            "quota" in normalized || "resource exhausted" in normalized ->
                "Gemini API 사용 한도를 초과했습니다. 잠시 후 다시 시도하거나 프로젝트 할당량을 확인해주세요."
            else -> raw
        }
    }
}
