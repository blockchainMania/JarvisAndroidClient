package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.AppContextProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.VisualMemoryFrameStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommandParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.CalendarActionManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.ContactActionManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val contactActionManager = ContactActionManager()
    private val calendarActionManager = CalendarActionManager()
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private var commandSpeechRecognizer: KoreanSpeechRecognizer? = null
    private var visualContextSentForTurn: Boolean = false
    private var stateObservationJob: Job? = null
    private var reconnectJob: Job? = null
    private var userRequestedStop: Boolean = false
    private var reconnectAttempts: Int = 0
    @Volatile
    private var inputAudioSuspended: Boolean = false
    private var inFlightToolCalls: Int = 0
    private var wakeWordSessionActive: Boolean = false
    private var pendingInitialText: String? = null
    private var lastHandledVoiceCommand: MeetingVoiceCommand? = null
    private var lastSentSpeechText: String? = null
    private var lastSentSpeechAt: Long = 0L

    private val _voiceCommands = MutableSharedFlow<MeetingVoiceCommand>(extraBufferCapacity = 4)
    val voiceCommands: SharedFlow<MeetingVoiceCommand> = _voiceCommands.asSharedFlow()

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    fun startSession(wakeWordInitiated: Boolean = false, initialText: String? = null) {
        if (_uiState.value.isGeminiActive) return
        userRequestedStop = false
        wakeWordSessionActive = wakeWordInitiated
        pendingInitialText = initialText?.trim().orEmpty().ifBlank { null }

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isGeminiActive = true)

        commandSpeechRecognizer?.stop()
        commandSpeechRecognizer = KoreanSpeechRecognizer(
            context = AppContextProvider.require(),
            onPartialText = { text ->
                _uiState.value = _uiState.value.copy(
                    userTranscript = text,
                    aiTranscript = "",
                )
            },
            onFinalText = { text ->
                handleRecognizedSpeech(text)
            },
            onErrorText = { message ->
                _uiState.value = _uiState.value.copy(errorMessage = message)
            },
        )

        geminiService.onAudioReceived = { data ->
            commandSpeechRecognizer?.setSuspended(true)
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
            commandSpeechRecognizer?.setSuspended(false)
        }

        geminiService.onTurnComplete = {
            visualContextSentForTurn = false
            _uiState.value = _uiState.value.copy(userTranscript = "")
            commandSpeechRecognizer?.setSuspended(false)
            if (wakeWordSessionActive) {
                viewModelScope.launch {
                    delay(250L)
                    if (_uiState.value.isGeminiActive && wakeWordSessionActive) {
                        stopSession()
                    }
                }
            }
        }

        geminiService.onInputTranscription = { text ->
            val transcript = _uiState.value.userTranscript + text
            _uiState.value = _uiState.value.copy(
                userTranscript = transcript,
                aiTranscript = ""
            )
            val command = MeetingVoiceCommandParser.parse(transcript)
            if (command != null && command != lastHandledVoiceCommand) {
                lastHandledVoiceCommand = command
                _voiceCommands.tryEmit(command)
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

            geminiService.onToolCall = toolCallHandler@{ toolCall ->
                inFlightToolCalls += toolCall.functionCalls.size
                inputAudioSuspended = true
                commandSpeechRecognizer?.setSuspended(true)
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
            geminiService.connect(
                requireWakeWord = wakeWordSessionActive,
                speechContextHint = contactActionManager.speechContextHint(),
            ) { setupOk ->
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

                // Gemini keeps audio output, while Android ko-KR SpeechRecognizer handles user input.
                try {
                    audioManager.startPlayback()
                    commandSpeechRecognizer?.start()
                    reconnectAttempts = 0
                    pendingInitialText?.let { firstQuery ->
                        _uiState.value = _uiState.value.copy(userTranscript = firstQuery)
                        geminiService.sendTextMessage(firstQuery)
                        pendingInitialText = null
                    }
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
        stopSession(userInitiated = true)
    }

    fun stopSession(userInitiated: Boolean) {
        if (userInitiated) {
            userRequestedStop = true
        }
        reconnectJob?.cancel()
        reconnectJob = null
        eventClient.disconnect()
        toolCallRouter?.cancelAll()
        toolCallRouter = null
        commandSpeechRecognizer?.stop()
        commandSpeechRecognizer = null
        audioManager.stopCapture()
        geminiService.disconnect()
        stateObservationJob?.cancel()
        stateObservationJob = null
        visualContextSentForTurn = false
        inputAudioSuspended = false
        inFlightToolCalls = 0
        wakeWordSessionActive = false
        pendingInitialText = null
        lastHandledVoiceCommand = null
        lastSentSpeechText = null
        lastSentSpeechAt = 0L
        _uiState.value = GeminiUiState()
    }

    private fun handleRecognizedSpeech(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text == lastSentSpeechText && now - lastSentSpeechAt < 2_000L) return
        lastSentSpeechText = text
        lastSentSpeechAt = now

        _uiState.value = _uiState.value.copy(
            userTranscript = text,
            aiTranscript = "",
        )

        val command = MeetingVoiceCommandParser.parse(text)
        if (command != null && command != lastHandledVoiceCommand) {
            lastHandledVoiceCommand = command
            _voiceCommands.tryEmit(command)
            return
        }

        if (_uiState.value.connectionState == GeminiConnectionState.Ready && !geminiService.isModelSpeaking.value) {
            geminiService.sendTextMessage(text)
        }
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
        val resumeWakeWordMode = wakeWordSessionActive
        reconnectJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini disconnected. Reconnecting..."
            )
            eventClient.disconnect()
            toolCallRouter?.cancelAll()
            toolCallRouter = null
            commandSpeechRecognizer?.stop()
            commandSpeechRecognizer = null
            audioManager.stopCapture()
            geminiService.disconnect()
            stateObservationJob?.cancel()
            stateObservationJob = null
            visualContextSentForTurn = false
            inputAudioSuspended = false
            inFlightToolCalls = 0
            pendingInitialText = null
            lastHandledVoiceCommand = null
            lastSentSpeechText = null
            lastSentSpeechAt = 0L
            _uiState.value = GeminiUiState()
            delay(1_200L)
            startSession(wakeWordInitiated = resumeWakeWordMode)
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
            "call_contact" -> callContact(call)
            "text_contact" -> textContact(call)
            "create_contact" -> createContact(call)
            "create_calendar_event" -> createCalendarEvent(call)
            else -> ToolResult.Failure("Unknown local tool: ${call.name}")
        }
    }

    private suspend fun callContact(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult.Failure("전화할 연락처 이름을 알려주세요.")
        }
        return contactActionManager.callContact(query)
    }

    private suspend fun textContact(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"]?.toString()?.trim().orEmpty()
        val message = call.args["message"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult.Failure("문자를 보낼 연락처 이름을 알려주세요.")
        }
        return contactActionManager.textContact(query, message)
    }

    private suspend fun createContact(call: GeminiFunctionCall): ToolResult {
        val name = call.args["name"]?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            return ToolResult.Failure("저장할 연락처 이름을 알려주세요.")
        }
        return contactActionManager.createContact(
            name = name,
            phone = call.args["phone"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            email = call.args["email"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            org = call.args["org"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            role = call.args["role"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            notes = call.args["notes"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun createCalendarEvent(call: GeminiFunctionCall): ToolResult {
        val title = call.args["title"]?.toString()?.trim().orEmpty()
        val startAt = call.args["start_at"]?.toString()?.trim().orEmpty()
        if (title.isBlank() || startAt.isBlank()) {
            return ToolResult.Failure("일정 제목과 시작 시간이 필요합니다.")
        }
        return calendarActionManager.createEvent(
            title = title,
            startAt = startAt,
            endAt = call.args["end_at"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            location = call.args["location"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            description = call.args["description"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
        )
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
                commandSpeechRecognizer?.setSuspended(false)
                Log.d(TAG, "Resumed Gemini input after tool response")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
