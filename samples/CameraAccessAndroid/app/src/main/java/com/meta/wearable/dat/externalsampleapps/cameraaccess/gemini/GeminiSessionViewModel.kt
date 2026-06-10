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
    private var visualContextSentForTurn: Boolean = false
    private var stateObservationJob: Job? = null
    private var reconnectJob: Job? = null
    private var userRequestedStop: Boolean = false
    private var reconnectAttempts: Int = 0
    @Volatile
    private var inputAudioSuspended: Boolean = false
    private var inFlightToolCalls: Int = 0
    private var wakeWordDetectedForTurn: Boolean = false

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
            if (inputAudioSuspended) return@lambda
            // Do not feed speaker output or room speech back into a response in progress.
            if (geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            if (wakeWordDetectedForTurn) {
                audioManager.playAudio(data)
            }
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onTurnComplete = {
            visualContextSentForTurn = false
            wakeWordDetectedForTurn = false
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        geminiService.onInputTranscription = { text ->
            val transcript = _uiState.value.userTranscript + text
            if (hasWakeWord(transcript)) {
                wakeWordDetectedForTurn = true
            }
            _uiState.value = _uiState.value.copy(
                userTranscript = transcript,
                aiTranscript = ""
            )
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

            geminiService.onToolCall = toolCallHandler@{ toolCall ->
                if (!wakeWordDetectedForTurn) {
                    Log.d(TAG, "Ignoring tool call because the current turn has no wake word")
                    for (call in toolCall.functionCalls) {
                        geminiService.sendToolResponse(
                            ToolCallRouter.buildImmediateResponse(
                                call,
                                ToolResult.Failure(
                                    "Ignored because the user did not start this turn with the wake word 자비스."
                                ),
                            )
                        )
                    }
                    return@toolCallHandler
                }

                inFlightToolCalls += toolCall.functionCalls.size
                inputAudioSuspended = true
                Log.d(TAG, "Suspending Gemini input for ${toolCall.functionCalls.size} tool call(s)")
                for (call in toolCall.functionCalls) {
                    toolCallRouter?.handleToolCall(call) { response ->
                        geminiService.sendToolResponse(response)
                        onToolCallFinished()
                    }
                }
            }

            geminiService.onToolCallCancellation = { cancellation ->
                val cancelledCount = toolCallRouter?.cancelToolCalls(cancellation.ids) ?: 0
                repeat(cancelledCount) {
                    onToolCallFinished()
                }
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
        inputAudioSuspended = false
        inFlightToolCalls = 0
        wakeWordDetectedForTurn = false
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
            inputAudioSuspended = false
            inFlightToolCalls = 0
            wakeWordDetectedForTurn = false
            _uiState.value = GeminiUiState()
            delay(1_200L)
            startSession()
        }
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        VisualMemoryFrameStore.update(bitmap)
        // Keep the latest frame locally. Visual data is sent to Gemini only after an explicit
        // capture_current_view tool call so an older stream frame cannot answer a new question.
    }

    private suspend fun handleLocalToolCall(call: GeminiFunctionCall): ToolResult {
        return when (call.name) {
            "capture_current_view" -> captureCurrentView(call)
            else -> ToolResult.Failure("Unknown local tool: ${call.name}")
        }
    }

    private suspend fun captureCurrentView(call: GeminiFunctionCall): ToolResult {
        val visualFrame = VisualMemoryFrameStore.captureFreshVisual()
            ?: return ToolResult.Failure(
                "No fresh camera image is available. Ask the user to hold still and retry."
            )

        geminiService.sendVideoFrameBase64(visualFrame.base64)
        visualContextSentForTurn = true
        // Let the ordered Live API image message arrive before the tool response tells the model
        // to answer. Without this, the model can answer from older conversation imagery.
        delay(350L)

        val reason = call.args["reason"]?.toString() ?: "current visual context"
        Log.d(
            TAG,
            "capture_current_view sent ${visualFrame.source}, ${visualFrame.width}x${visualFrame.height}, ${visualFrame.jpegBytes} bytes, reason=$reason"
        )
        return ToolResult.Success(
            "Attached one fresh camera image from ${visualFrame.source} (${visualFrame.width}x${visualFrame.height}, ${visualFrame.jpegBytes} bytes, ${visualFrame.ageMs}ms old) to this conversation. Use the image to answer the user's visual question or to fill ai_interpretation before saving. If the image is unclear or too low resolution, say that you are not sure and ask the user to hold still or move closer. reason=$reason"
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun onToolCallFinished() {
        inFlightToolCalls = (inFlightToolCalls - 1).coerceAtLeast(0)
        if (inFlightToolCalls > 0) return

        viewModelScope.launch {
            // Avoid feeding room audio into Gemini while it starts speaking after a tool response.
            delay(800L)
            if (_uiState.value.isGeminiActive) {
                inputAudioSuspended = false
                Log.d(TAG, "Resumed Gemini input after tool response")
            }
        }
    }

    private fun hasWakeWord(transcript: String): Boolean {
        val normalized = transcript
            .lowercase()
            .replace(Regex("[^가-힣a-z0-9]"), "")
        return normalized.startsWith("자비스") || normalized.startsWith("jarvis")
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
