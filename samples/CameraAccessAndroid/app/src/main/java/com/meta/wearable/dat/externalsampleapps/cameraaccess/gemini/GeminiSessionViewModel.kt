package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.VisualMemoryFrameStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
)

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val geminiService = GeminiLiveService()
    private val openClawBridge = OpenClawBridge()
    private var toolCallRouter: ToolCallRouter? = null
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private var lastVideoFrameTime: Long = 0
    private var visualContextSentForTurn: Boolean = false
    private var lastOnDemandVisualContextAt: Long = 0
    private var stateObservationJob: Job? = null
    private var reconnectJob: Job? = null
    private var userRequestedStop: Boolean = false
    private var reconnectAttempts: Int = 0
    private var isRecordingTranscript: Boolean = false
    private var recordingStartedAtMs: Long = 0L
    private var recordingTitle: String = ""
    private val recordingTranscript = StringBuilder()

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    fun startSession() {
        if (_uiState.value.isGeminiActive) return
        userRequestedStop = false

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isGeminiActive = true)

        // Wire audio callbacks
        audioManager.onAudioCaptured = lambda@{ data ->
            // Phone mode: mute mic while model speaks to prevent echo
            if (streamingMode == StreamingMode.PHONE && geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onTurnComplete = {
            visualContextSentForTurn = false
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        geminiService.onInputTranscription = { text ->
            val transcript = _uiState.value.userTranscript + text
            _uiState.value = _uiState.value.copy(
                userTranscript = transcript,
                aiTranscript = ""
            )
            if (isRecordingTranscript) {
                recordingTranscript.append(text)
            }
            viewModelScope.launch {
                maybeSendOnDemandVisualContext(transcript)
            }
        }

        geminiService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
        }

        geminiService.onDisconnected = { reason ->
            if (_uiState.value.isGeminiActive && !userRequestedStop) {
                scheduleReconnect(reason)
            }
        }

        // Check OpenClaw and start session
        viewModelScope.launch {
            openClawBridge.checkConnection()
            openClawBridge.resetSession()

            // Wire tool call handling
            toolCallRouter = ToolCallRouter(
                openClawBridge,
                viewModelScope,
                localToolHandler = ::handleLocalToolCall,
            )

            geminiService.onToolCall = { toolCall ->
                for (call in toolCall.functionCalls) {
                    toolCallRouter?.handleToolCall(call) { response ->
                        geminiService.sendToolResponse(response)
                    }
                }
            }

            geminiService.onToolCallCancellation = { cancellation ->
                toolCallRouter?.cancelToolCalls(cancellation.ids)
            }

            // Observe service state
            stateObservationJob = viewModelScope.launch {
                while (isActive) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(
                        connectionState = geminiService.connectionState.value,
                        isModelSpeaking = geminiService.isModelSpeaking.value,
                        toolCallStatus = openClawBridge.lastToolCallStatus.value,
                        openClawConnectionState = openClawBridge.connectionState.value,
                    )
                }
            }

            // Connect to Gemini
            geminiService.connect { setupOk ->
                if (!setupOk) {
                    val msg = when (val state = geminiService.connectionState.value) {
                        is GeminiConnectionState.Error -> state.message
                        else -> "Failed to connect to Gemini"
                    }
                    _uiState.value = _uiState.value.copy(errorMessage = msg)
                    geminiService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                    return@connect
                }

                // Start mic capture
                try {
                    audioManager.startCapture()
                    reconnectAttempts = 0
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mic capture failed: ${e.message}"
                    )
                    geminiService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                }

                // Connect to OpenClaw event stream for proactive notifications
                if (SettingsManager.proactiveNotificationsEnabled) {
                    eventClient.onNotification = { text ->
                        val state = _uiState.value
                        if (state.isGeminiActive && state.connectionState == GeminiConnectionState.Ready) {
                            geminiService.sendTextMessage(text)
                        }
                    }
                    eventClient.connect()
                }
            }
        }
    }

    fun stopSession() {
        userRequestedStop = true
        reconnectJob?.cancel()
        reconnectJob = null
        eventClient.disconnect()
        toolCallRouter?.cancelAll()
        toolCallRouter = null
        audioManager.stopCapture()
        geminiService.disconnect()
        stateObservationJob?.cancel()
        stateObservationJob = null
        visualContextSentForTurn = false
        isRecordingTranscript = false
        recordingTranscript.clear()
        _uiState.value = GeminiUiState()
    }

    private fun scheduleReconnect(reason: String?) {
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempts >= 2) {
            stopSession()
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini connection lost: ${reason ?: "Unknown error"}"
            )
            return
        }
        reconnectAttempts += 1
        reconnectJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini disconnected. Reconnecting..."
            )
            eventClient.disconnect()
            toolCallRouter?.cancelAll()
            toolCallRouter = null
            audioManager.stopCapture()
            geminiService.disconnect()
            stateObservationJob?.cancel()
            stateObservationJob = null
            visualContextSentForTurn = false
            _uiState.value = GeminiUiState()
            delay(1_200L)
            startSession()
        }
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        VisualMemoryFrameStore.update(bitmap)
        if (!SettingsManager.videoStreamingEnabled) return
        if (!_uiState.value.isGeminiActive) return
        if (_uiState.value.connectionState != GeminiConnectionState.Ready) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < GeminiConfig.VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        geminiService.sendVideoFrame(bitmap)
    }

    private suspend fun maybeSendOnDemandVisualContext(transcript: String) {
        if (visualContextSentForTurn) return
        if (!_uiState.value.isGeminiActive) return
        if (_uiState.value.connectionState != GeminiConnectionState.Ready) return
        if (!looksLikeVisualRequest(transcript)) return

        val now = System.currentTimeMillis()
        if (now - lastOnDemandVisualContextAt < 3_000L) return
        val visualFrame = VisualMemoryFrameStore.captureFreshVisual() ?: return

        visualContextSentForTurn = true
        lastOnDemandVisualContextAt = now
        geminiService.sendVideoFrameBase64(visualFrame.base64)
        Log.d(
            TAG,
            "Sent one on-demand visual frame (${visualFrame.source}, ${visualFrame.ageMs}ms) for transcript=${transcript.take(80)}"
        )
    }

    private suspend fun handleLocalToolCall(call: GeminiFunctionCall): ToolResult {
        return when (call.name) {
            "capture_current_view" -> captureCurrentView(call)
            "start_recording" -> startRecording(call)
            "stop_recording" -> stopRecording(call)
            else -> ToolResult.Failure("Unknown local tool: ${call.name}")
        }
    }

    private fun startRecording(call: GeminiFunctionCall): ToolResult {
        isRecordingTranscript = true
        recordingStartedAtMs = System.currentTimeMillis()
        recordingTitle = call.args["title"]?.toString().orEmpty()
        recordingTranscript.clear()
        Log.d(TAG, "Recording transcript started: $recordingTitle")
        return ToolResult.Success(
            "Recording started. Keep listening until the user says to stop recording."
        )
    }

    private fun stopRecording(call: GeminiFunctionCall): ToolResult {
        if (!isRecordingTranscript) {
            return ToolResult.Failure("Recording is not active.")
        }
        isRecordingTranscript = false
        val endedAtMs = System.currentTimeMillis()
        val durationSec = ((endedAtMs - recordingStartedAtMs).coerceAtLeast(0L) / 1000L)
        val transcript = recordingTranscript.toString().trim()
        recordingTranscript.clear()
        Log.d(TAG, "Recording transcript stopped: ${durationSec}s, ${transcript.length} chars")
        return ToolResult.Success(
            """
            Recording stopped.
            title=${recordingTitle.ifBlank { "untitled" }}
            duration_seconds=$durationSec
            transcript=$transcript

            Summarize this transcript in Korean. Include: 핵심 요약, 결정사항, 할일, 언급된 사람/회사. If transcript is empty, tell the user no speech was captured.
            """.trimIndent()
        )
    }

    private suspend fun captureCurrentView(call: GeminiFunctionCall): ToolResult {
        val visualFrame = VisualMemoryFrameStore.captureFreshVisual()
            ?: return ToolResult.Failure(
                "No fresh camera image is available. Ask the user to hold still and retry."
            )

        geminiService.sendVideoFrameBase64(visualFrame.base64)
        visualContextSentForTurn = true
        lastOnDemandVisualContextAt = System.currentTimeMillis()

        val reason = call.args["reason"]?.toString() ?: "current visual context"
        Log.d(TAG, "capture_current_view sent ${visualFrame.source}, reason=$reason")
        return ToolResult.Success(
            "Attached one fresh camera image from ${visualFrame.source} (${visualFrame.ageMs}ms old) to this conversation. Use the image to answer the user's visual question or to fill ai_interpretation before saving. reason=$reason"
        )
    }

    private fun looksLikeVisualRequest(transcript: String): Boolean {
        val normalized = transcript.lowercase()
        val visualReferences = listOf(
            "이거",
            "이것",
            "저거",
            "저것",
            "앞에",
            "눈앞",
            "보고 있는",
            "지금 보는",
            "현재 보는",
            "보이는",
            "화면",
            "장면",
            "이미지",
            "사진",
            "문서",
            "명함",
            "화이트보드",
            "차",
            "자동차",
            "색",
            "색깔",
            "물건",
            "제품",
            "재료",
            "음식",
            "간판",
            "텍스트",
        )
        val visualActions = listOf(
            "저장",
            "기억",
            "뭐",
            "무엇",
            "뭔",
            "누구",
            "어디",
            "왜",
            "어때",
            "맞",
            "다시",
            "읽",
            "요약",
            "설명",
            "해석",
            "찾아",
            "알려",
            "보여",
        )
        return visualReferences.any { normalized.contains(it) } &&
            visualActions.any { normalized.contains(it) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
