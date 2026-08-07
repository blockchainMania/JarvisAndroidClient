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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.whisper.WhisperSpeechRecognizer
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
    val pendingContactAction: PendingContactAction? = null,
)

enum class PendingContactActionType {
    CALL,
    TEXT,
}

data class PendingContactAction(
    val type: PendingContactActionType,
    val query: String,
    val message: String? = null,
    // Set when the root agent already resolved an exact number (e.g. a Jarvis-saved person's
    // `phone` field via universal_search) -- lets confirmPendingContactAction dial/text it
    // directly instead of re-resolving through Android's contacts book by name.
    val phoneNumber: String? = null,
)

data class PendingToolConfirmation(
    val name: String,
    val args: Map<String, Any?>,
)

// Short-lived cache of the last successful capture_current_view read (business card, document,
// etc.), so a follow-up save-intent utterance ("저장해줘") doesn't have to re-trigger a whole new
// photo capture just to re-derive text it already extracted a few seconds ago.
data class CachedVisualRead(
    val answer: String,
    val capturedAtMs: Long,
    val frame: VisualMemoryFrameStore.VisualFrame,
    val businessCard: GeminiFlashVisionClient.BusinessCard? = null,
)

// One (user utterance, final spoken answer) pair kept for short-term conversational memory --
// see MAX_CONVERSATION_HISTORY_TURNS in GeminiSessionViewModel.
data class ConversationTurn(
    val userText: String,
    val answerText: String,
    val atMs: Long,
)

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"
        private const val TTS_ONLY_PREFIX = "[TTS 전용] "
        private const val MAX_ROOT_AGENT_STEPS = 4

        // Tools that write to the Jarvis backend must be confirmed by the user before they
        // actually run -- the root agent proposes args + a spoken confirm question but the
        // app withholds dispatch until the next turn matches a confirm phrase.
        private val CONFIRM_REQUIRED_TOOLS = setOf(
            "save_person", "save_meeting", "save_memory", "save_life_memory", "save_need",
        )

        // If the user's own utterance already carries explicit save intent ("저장해줘",
        // "기억해줘"...), asking "이렇게 저장할까요?" again is a redundant round trip -- the
        // request itself is the confirmation. The confirm gate stays in place for cases where
        // the AI proposes a save the user didn't ask for (e.g. identify_person's no-match flow).
        private val SAVE_INTENT_KEYWORDS = listOf("저장", "기억해", "등록", "기록")

        // "내 앞에 있는 아이/사람"처럼 지극히 자연스러운 person-reference 표현도
        // VisionQuestionDetector의 "앞에있는" 키워드와 겹친다. 그 경우 save_person/
        // identify_person을 아는 루트 에이전트가 아니라 문서/사물 판독용 GeminiFlashVisionClient로
        // 새서 얼굴 저장/조회가 통째로 우회되는 게 실제로 확인된 버그다 -- 저장/조회 의도가 있으면
        // VisionQuestionDetector 단축 경로를 절대 타면 안 된다.
        private val IDENTIFY_INTENT_KEYWORDS = listOf("누구야", "누구게", "누구지", "누구니")

        // A save-intent utterance that also names a person ("사람", "아이", "얘", "분") is asking
        // to register a person's FACE (save_person + attach_current_photo), not a document/
        // business card (save_life_memory). Must never take the cachedVisualRead direct-save
        // shortcut below -- that shortcut only knows how to call save_life_memory, which has no
        // face embedding step at all, so a person "saved" through it can never later be found by
        // identify_person.
        private val PERSON_SAVE_KEYWORDS = listOf("사람", "아이", "얘", "분")

        // A save-intent utterance naming neither a person noun above nor a document noun here
        // (e.g. a bare "홍길동으로 저장해줘", the earliest reported case of this bug this session)
        // is ambiguous but still must not take the person-face shortcut below -- see
        // isPersonFaceSaveIntent. Deliberately asymmetric: a false positive here costs one
        // redundant photo on what turns out to be a document save; a false negative silently
        // reproduces the face-less-save bug this whole guard exists to prevent.
        private val DOCUMENT_SAVE_KEYWORDS = listOf("명함", "문서", "카드", "메모", "종이")

        // Present in the utterance, this means the user explicitly wants a fresh look (not a
        // cached read reused), so cache reuse must be skipped even if a recent one exists.
        private val RECAPTURE_KEYWORDS = listOf("다시")

        // How long a cached capture_current_view read stays eligible for reuse by a later
        // save-intent utterance before it's considered stale (the camera view may have changed).
        private const val VISUAL_READ_CACHE_TTL_MS = 90_000L

        // How long a terminal tool-call status ("저장 완료" etc.) stays on screen before the
        // banner auto-hides.
        private const val TOOL_STATUS_AUTO_DISMISS_MS = 3_500L

        // Short-term conversational memory: each root agent call is otherwise stateless (see
        // JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2), so a follow-up like "연락처도 알려줘"
        // had nothing to anchor "누구?" to. Replaying just the last few (user text, final spoken
        // answer) pairs -- not raw tool results -- gives the model enough to re-run its own
        // search with the missing context filled in, without ballooning prompt size.
        private const val MAX_CONVERSATION_HISTORY_TURNS = 6
        private const val CONVERSATION_HISTORY_TTL_MS = 5 * 60_000L

        private const val ROOT_AGENT_SYSTEM_INSTRUCTION = """당신은 Jarvis 개인 비서의 판단 담당(루트 에이전트)입니다. 사용자 발화(음성 인식된 텍스트)를 보고 어떤 도구를 호출할지, 또는 도구 없이 바로 답할지 결정하세요. 당신의 텍스트 답변은 다른 컴포넌트가 그대로 소리 내어 읽으므로, 자연스러운 한국어 구어체로 짧게 쓰세요.

[핵심 원칙]
- 도구 없이 이미 아는 사실로 답할 수 있으면 도구를 호출하지 말고 바로 텍스트로 답하세요.
- 과거 기억/사람/미팅/니즈 관련 질문은 항상 universal_search를 우선 사용하세요.
- 현재 시야(카메라)가 필요한 질문이나 저장 요청이면 capture_current_view를 먼저 호출하세요. 그 결과는 이미 완성된 판독 텍스트입니다 -- 다시 캡처를 요청하지 말고 그 내용을 근거로 다음 행동을 결정하세요. 단, **사람을 저장/등록하는 요청(아래 참고)에는 이 규칙을 적용하지 마세요** -- capture_current_view는 명함/문서/사물을 "읽는" 용도이고 사람 얼굴 품질 판정에는 안 맞습니다.
- 전화/문자 요청은 먼저 universal_search로 사람 후보를 찾으세요. 찾은 사람(person/person_candidates)에 phone이 있으면 call_contact/text_contact의 phone_number에 그 번호를 그대로 넣어 호출하세요 -- Android 전화번호부에 없는 사람도 이렇게 걸고 보낼 수 있습니다. phone이 없으면 search_contacts로 Android 연락처 후보를 찾아 query로 호출하세요. 어느 경우든 사용자가 대상을 확정한 뒤에만 호출하고, 후보가 여럿이면 도구를 호출하지 말고 텍스트로 후보를 제시해 확인을 구하세요.
- 메일 요청도 마찬가지로 먼저 universal_search로 사람을 찾아 person/person_candidates의 email을 send_email의 to에 넣으세요. send_email은 메일 작성 화면만 열고 실제 발송은 사용자가 앱에서 직접 눌러야 하니, 제목/본문을 사용자가 말한 내용을 바탕으로 자연스럽게 작성해 넣고 호출하세요.
- save_person, save_meeting, save_memory, save_life_memory, save_need를 호출하기로 결정했으면, 반드시 같은 응답에 텍스트로 "이 내용으로 저장할까요?" 같은 확인 질문도 함께 포함하세요. 이 확인 질문을 사용자가 승인한 뒤에만 실제로 저장이 실행됩니다.
- 명함을 저장할 때 확인 질문은 이름/회사명/직책/전화번호/이메일주소/회사주소 중 실제로 확인된 항목만 나열해서 물으세요. 예: "이름은 김민수 팀장, 회사는 ABC상사, 전화번호는 010-1234-5678로 인식했어요. 이렇게 저장해드릴까요?" 확인 안 된 항목은 언급하지 말고("전화번호는 없음" 같은 말 하지 말고) 그냥 빼세요.
- 사용자가 "이 사람 누구야?", "얘 이름 뭐였지?"처럼 지금 보이는 사람이 누구인지 물으면 identify_person을 호출하세요(얼굴로 찾는 것이므로 capture_current_view가 아니라 identify_person을 씁니다). identify_person은 아직 유사도 컷오프가 없어서 실제로는 안 닮은 사람이어도 가장 가까운 후보를 반환합니다 -- **top 후보의 score가 0.4 미만이면 그 사람이라고 절대 단정하지 말고**, 처음 뵙는 분 같다고 말하고 이름을 물어본 뒤 save_person(name, attach_current_photo=true)으로 등록을 제안하세요(이 0.4는 실제 점수 분포가 더 쌓이기 전까지의 잠정 기준입니다). score가 0.4 이상이어서 확신이 서는 매치를 찾았으면, 이름만 답하지 말고 곧바로 그 person의 id로 get_proposal_context를 호출해 소속/직책/연락처(명함 정보)와 최근 미팅 이력을 함께 가져온 뒤, 이름과 함께 자연스럽게 요약해서 답하세요(예: "OOO님이에요. ABC상사 팀장이시고, 지난주에 미팅하셨네요."). 특별히 아는 게 없으면 이름만 말해도 되지만, get_proposal_context 호출 자체는 항상 먼저 시도하세요.
- 사용자가 "이 사람 사진 찍어서 저장해줘", "내 앞에 있는 사람 OO로 저장해줘"처럼 지금 보이는 사람을 사진과 함께 등록해달라고 하면, **capture_current_view는 절대 호출하지 말고** 곧장 save_person을 호출하되 attach_current_photo를 true로 하세요. 얼굴 사진 캡처와 품질 판정은 save_person(attach_current_photo=true) 내부에서 전용 얼굴 인식 모델이 처리합니다 -- capture_current_view로 먼저 확인하면 명함/문서 판독용 모델이 얼굴을 잘못 판정해서 "밝은 곳에서 다시 찍어달라"처럼 부정확한 안내를 낼 수 있습니다.
- identify_person이나 attach_current_photo를 쓴 save_person의 결과가 "여러 사람이 보여서" 같은 에러를 반환하면, 추측해서 아무 이름이나 대지 말고 그 문장 그대로(또는 비슷한 뜻으로) 사용자에게 전달해 한 사람만 나오게 다시 비춰달라고 요청하세요.
- 이미지에서 실제로 보이거나 사용자가 말한 내용만 사용하고, 확실하지 않은 이름/번호/내용을 지어내지 마세요.
- 시간 표현은 한국 시간(Asia/Seoul) 기준으로 계산해 ISO 8601로 넘기세요.
- 매 응답마다 반드시 도구 호출 또는 텍스트 답변 중 하나는 있어야 합니다. 절대 아무 내용 없이 응답을 끝내지 마세요. 사용자의 요청이 여러 의도가 섞여 있거나 불명확해서 어떤 도구를 불러야 할지 모르겠으면, 도구를 호출하지 말고 무엇을 원하시는지 되묻는 짧은 텍스트로 답하세요. 예를 들어 어떤 사진을 어떤 이름으로 저장할지 불명확하면, 다시 한번 말씀해달라고 되물으세요."""
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
    private var commandSpeechRecognizer: SpeechInputController? = null
    private var visualContextSentForTurn: Boolean = false
    private var stateObservationJob: Job? = null
    private var reconnectJob: Job? = null
    private var userRequestedStop: Boolean = false
    private var reconnectAttempts: Int = 0
    @Volatile
    private var inputAudioSuspended: Boolean = false
    // Guards the window between "STT recognized an utterance" and "its answer was handed to
    // Live for TTS" -- isModelSpeaking alone doesn't cover this, so a second utterance spoken
    // while the first is still awaiting its Flash/root-agent network round trip could start a
    // fully concurrent sendTextOrVisionAnswer/runRootAgent call. Whichever call's network
    // response happened to land last would "win" the TTS output regardless of question order,
    // which is what a one-turn-behind-looking answer actually was.
    private var isProcessingUtterance: Boolean = false
    private var inFlightToolCalls: Int = 0
    private var wakeWordSessionActive: Boolean = false
    private var pendingInitialText: String? = null
    private var lastHandledVoiceCommand: MeetingVoiceCommand? = null
    private var lastSentSpeechText: String? = null
    private var lastSentSpeechAt: Long = 0L
    private var pendingSpeechText: String? = null
    private var pendingSpeechJob: Job? = null
    private var usingFallbackAndroidStt: Boolean = false
    private var pendingToolConfirmation: PendingToolConfirmation? = null
    private var cachedVisualRead: CachedVisualRead? = null
    // True whenever the answer just spoken is itself a question back to the user (a save
    // confirmation, a disambiguation like "어떤 김윤섭인가요?", or a re-ask after an empty/failed
    // result) -- set in speakAndRemember from whether that answer text ends in "?". Wake-word
    // sessions auto-stop ~250ms after TTS finishes speaking (see onTurnComplete below); without
    // this guard that teardown clears conversationHistory before the user can even reply, so
    // their answer arrives as a brand new utterance with zero memory of what was asked --
    // producing exactly the "keeps asking questions back and never answers" loop this guards.
    private var expectingReply: Boolean = false
    private val conversationHistory = ArrayDeque<ConversationTurn>()

    private val _voiceCommands = MutableSharedFlow<MeetingVoiceCommand>(extraBufferCapacity = 4)
    val voiceCommands: SharedFlow<MeetingVoiceCommand> = _voiceCommands.asSharedFlow()

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    fun startSession(wakeWordInitiated: Boolean = false, initialText: String? = null) {
        if (_uiState.value.isGeminiActive) return
        userRequestedStop = false
        wakeWordSessionActive = wakeWordInitiated
        pendingInitialText = initialText?.trim().orEmpty().ifBlank { null }
        usingFallbackAndroidStt = false

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isGeminiActive = true)

        commandSpeechRecognizer?.stop()
        commandSpeechRecognizer = createSpeechInputController()
        _uiState.value = _uiState.value.copy(
            userTranscript = "",
            aiTranscript = "",
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
                    if (
                        _uiState.value.isGeminiActive &&
                        wakeWordSessionActive &&
                        _uiState.value.pendingContactAction == null &&
                        !expectingReply
                    ) {
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
                // openClawBridge.lastToolCallStatus only ever moves Executing -> Completed/Failed
                // and stays there -- nothing resets it back to Idle, so a "저장 완료" banner would
                // sit on screen forever. Track how long the current terminal status has been
                // showing and swap it for Idle (hides the banner) once it's been up long enough.
                var lastSeenStatus: ToolCallStatus = ToolCallStatus.Idle
                var lastSeenStatusAt = 0L
                while (isActive) {
                    delay(100)
                    val currentStatus = openClawBridge.lastToolCallStatus.value
                    if (currentStatus != lastSeenStatus) {
                        lastSeenStatus = currentStatus
                        lastSeenStatusAt = System.currentTimeMillis()
                    }
                    val isTerminal = currentStatus is ToolCallStatus.Completed ||
                        currentStatus is ToolCallStatus.Failed ||
                        currentStatus is ToolCallStatus.Cancelled
                    val displayStatus = if (
                        isTerminal && System.currentTimeMillis() - lastSeenStatusAt >= TOOL_STATUS_AUTO_DISMISS_MS
                    ) {
                        ToolCallStatus.Idle
                    } else {
                        currentStatus
                    }
                    _uiState.value = _uiState.value.copy(
                        connectionState = geminiService.connectionState.value,
                        isModelSpeaking = geminiService.isModelSpeaking.value,
                        toolCallStatus = displayStatus,
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
                        viewModelScope.launch { sendTextOrVisionAnswer(firstQuery) }
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
                            geminiService.sendTextMessage(TTS_ONLY_PREFIX + text)
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
        pendingSpeechJob?.cancel()
        pendingSpeechJob = null
        pendingSpeechText = null
        usingFallbackAndroidStt = false
        pendingToolConfirmation = null
        expectingReply = false
        cachedVisualRead = null
        conversationHistory.clear()
        isProcessingUtterance = false
        _uiState.value = GeminiUiState()
    }

    private fun handleRecognizedSpeech(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        val mergedText = mergePendingSpeech(text)
        pendingSpeechText = mergedText
        pendingSpeechJob?.cancel()
        pendingSpeechJob = viewModelScope.launch {
            // Secondary safety net for when KoreanSpeechRecognizer still splits an utterance
            // despite its own extended silence thresholds (see KoreanSpeechRecognizer.listen()) --
            // gives a late-arriving second fragment a bit more room to merge back in. This is
            // a deliberate speed/safety trade-off, not a full fix: catching every possible
            // fragmentation would need this to exceed EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            // (4.2s) *plus* however long the next fragment takes to speak, but that made every
            // single utterance (fragmented or not) feel like a ~5s hang before anything happened.
            // 2s covers the originally-reported ~2s mid-sentence pause while keeping the common
            // (non-fragmented) case responsive. If long pauses start dropping tails again, this
            // is the first knob to revisit.
            delay(2_000L)
            val pending = pendingSpeechText?.trim().orEmpty()
            pendingSpeechText = null
            pendingSpeechJob = null
            sendRecognizedSpeech(pending)
        }
        _uiState.value = _uiState.value.copy(
            userTranscript = mergedText,
            aiTranscript = "",
        )
    }

    private fun mergePendingSpeech(text: String): String {
        val pending = pendingSpeechText?.trim().orEmpty()
        if (pending.isBlank()) return text
        if (text == pending || text.startsWith(pending)) return text
        if (pending.endsWith(text)) return pending
        return "$pending $text"
    }

    private suspend fun sendRecognizedSpeech(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text == lastSentSpeechText && now - lastSentSpeechAt < 2_000L) return
        lastSentSpeechText = text
        lastSentSpeechAt = now

        _uiState.value = _uiState.value.copy(
            userTranscript = text,
            aiTranscript = "",
        )

        val pendingContactAction = _uiState.value.pendingContactAction
        if (pendingContactAction != null) {
            when {
                isContactActionCancel(text) -> {
                    cancelPendingContactAction()
                    return
                }
                isContactActionConfirm(text) -> {
                    confirmPendingContactAction()
                    return
                }
            }
        }

        val pendingTool = pendingToolConfirmation
        if (pendingTool != null) {
            when {
                isContactActionCancel(text) -> {
                    cancelPendingToolCall()
                    return
                }
                isContactActionConfirm(text) -> {
                    confirmPendingToolCall()
                    return
                }
            }
        }

        val command = MeetingVoiceCommandParser.parse(text)
        if (command != null && command != lastHandledVoiceCommand) {
            lastHandledVoiceCommand = command
            _voiceCommands.tryEmit(command)
            return
        }

        if (_uiState.value.connectionState == GeminiConnectionState.Ready && !geminiService.isModelSpeaking.value) {
            if (isProcessingUtterance) {
                Log.d(TAG, "Dropping utterance, still awaiting the previous one's answer: $text")
                return
            }
            isProcessingUtterance = true
            try {
                sendTextOrVisionAnswer(text)
            } finally {
                isProcessingUtterance = false
            }
        }
    }

    /**
     * Vision questions ("what's this", "check again") no longer rely on Gemini Live's own
     * judgment (send image+text and hope it doesn't re-call capture_current_view and hit the
     * same ordering race). Instead: capture a frame locally, get a stateless answer from
     * Gemini 2.5 Flash (single request/response, no session state to race against), then hand
     * Gemini Live only the finished answer text to speak verbatim -- Live is TTS here, not a
     * second judge. See JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 1.
     */
    private suspend fun sendTextOrVisionAnswer(text: String) {
        // Save-intent ("저장해줘"/"기억해줘"...) or identify-intent ("누구야"...) utterances must
        // go through the root agent even if they also match VisionQuestionDetector's keywords --
        // "내 앞에 있는 아이를 도현이라고 저장해줘" contains "앞에있는" just like a plain "what's
        // this" question does, but only the root agent knows about save_person/identify_person.
        // Short-circuiting straight to GeminiFlashVisionClient here meant person-save/identify
        // requests silently got treated as generic document/object questions instead -- face
        // recognition never even got invoked, which is exactly the reported "얼굴인식이 1도
        // 구현이 안됨" symptom.
        val hasPersonIntent = SAVE_INTENT_KEYWORDS.any { text.contains(it) } ||
            IDENTIFY_INTENT_KEYWORDS.any { text.contains(it) }
        if (!VisionQuestionDetector.matches(text) || hasPersonIntent) {
            runRootAgent(text)
            return
        }
        val visualFrame = VisualMemoryFrameStore.captureFreshVisual()
        if (visualFrame == null) {
            // No fresh frame available locally. Live no longer has vision-answering
            // instructions or tools of its own (it's TTS-only now), so there's nothing
            // useful to hand it here -- ask the user to retry instead of going silent.
            geminiService.sendTextMessage(
                TTS_ONLY_PREFIX + "지금 화면이 잘 안 보여요. 카메라 쪽을 봐주시고 다시 말씀해주시겠어요?"
            )
            return
        }
        visualContextSentForTurn = true
        Log.d(
            TAG,
            "Vision question -> Gemini 2.5 Flash: ${visualFrame.source}, ${visualFrame.ageMs}ms old",
        )
        val result = try {
            GeminiFlashVisionClient.answerVisionQuestion(question = text, imageBase64 = visualFrame.base64)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Flash vision call failed: ${e.message}")
            null
        }
        if (result == null || result.answer.isBlank()) {
            // Flash call failed to produce a usable answer.
            geminiService.sendTextMessage(
                TTS_ONLY_PREFIX + "지금 화면을 확인하는 데 문제가 있었어요. 다시 한번 말씀해주시겠어요?"
            )
            return
        }
        Log.d(TAG, "Gemini Flash canRead=${result.canRead}")
        // Same reasoning as captureCurrentView()'s cache: a save-intent follow-up right after
        // ("도현이라고 저장해줘") should reuse this read instead of forcing a brand new capture --
        // by then the subject may well have moved out of frame, as happened in testing.
        cachedVisualRead = CachedVisualRead(
            answer = result.answer,
            capturedAtMs = System.currentTimeMillis(),
            frame = visualFrame,
            businessCard = result.businessCard,
        )
        speakAndRemember(text, result.answer)
    }

    /**
     * The root agent (Gemini 2.5 Flash, stateless) decides which backend/local tool to call
     * and with what args -- Gemini Live never makes this decision anymore, it only speaks
     * whatever text this loop hands it. Each step is one generateContent request; a functionCall
     * step gets dispatched and its result fed back as the next turn (bounded, so a confused
     * model can't loop forever); a plain-text step is the final answer. See
     * JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2.
     */
    /**
     * Gemini occasionally returns a candidate with finishReason STOP and zero parts -- no
     * functionCall, no text -- for reasons that aren't a network/parse failure (seen in testing
     * on an ambiguous multi-intent utterance). One retry is enough to recover most of the time;
     * if it happens twice in a row, treat it like any other unreachable/unparseable failure.
     */
    private suspend fun stepWithRetry(contents: JSONArray, stepIndex: Int): GeminiRootAgentClient.RootAgentStep? {
        repeat(2) { attempt ->
            val step = try {
                GeminiRootAgentClient.step(contents, ROOT_AGENT_SYSTEM_INSTRUCTION)
            } catch (e: Exception) {
                Log.e(TAG, "Root agent step $stepIndex attempt $attempt failed: ${e.message}")
                null
            }
            // GeminiRootAgentClient.parseStep() returns a non-null RootAgentStep(null, null) when
            // Gemini's response has a "candidates[0].content.parts" array that's present but
            // literally empty (finishReason STOP, zero output tokens -- confirmed live in
            // api.err's "flash response: parts=EMPTY" logs, notably right after an
            // identify_person multi-face-error retry). That step is not null, so the `step !=
            // null` check below used to treat it as a finished (empty) answer and return
            // immediately without ever retrying -- runRootAgent then spoke "죄송해요, 답을 잘 못
            // 만들었어요" on literally the first attempt, and since every retry of the same
            // follow-up utterance re-hit this exact same empty-response quirk, it looked to the
            // user like every "다시 촬영해줘" after a multi-face error just failed outright.
            // Treat it the same as an exception: worth one retry before giving up for real.
            if (step != null && (step.functionCall != null || step.text != null)) return step
            Log.e(TAG, "Root agent step $stepIndex attempt $attempt returned an empty response")
        }
        return null
    }

    private fun pruneStaleConversationHistory() {
        val cutoff = System.currentTimeMillis() - CONVERSATION_HISTORY_TTL_MS
        while (conversationHistory.isNotEmpty() && conversationHistory.first().atMs < cutoff) {
            conversationHistory.removeFirst()
        }
    }

    private fun rememberConversationTurn(userText: String, answerText: String) {
        pruneStaleConversationHistory()
        conversationHistory.addLast(ConversationTurn(userText, answerText, System.currentTimeMillis()))
        while (conversationHistory.size > MAX_CONVERSATION_HISTORY_TURNS) {
            conversationHistory.removeFirst()
        }
    }

    /** Sends the final spoken answer for this turn and records it as conversational history in
     * one place, so no call site can send an answer without also remembering it. */
    private fun speakAndRemember(userText: String, answer: String) {
        expectingReply = answer.trim().endsWith("?")
        geminiService.sendTextMessage(TTS_ONLY_PREFIX + answer)
        rememberConversationTurn(userText, answer)
    }

    private suspend fun runRootAgent(text: String) {
        val hasExplicitSaveIntent = SAVE_INTENT_KEYWORDS.any { text.contains(it) }
        val isPersonFaceSaveIntent = hasExplicitSaveIntent &&
            (PERSON_SAVE_KEYWORDS.any { text.contains(it) } || DOCUMENT_SAVE_KEYWORDS.none { text.contains(it) })
        val wantsFreshCapture = RECAPTURE_KEYWORDS.any { text.contains(it) }

        val cachedSnapshot = cachedVisualRead
        val cacheIsFresh = cachedSnapshot != null && !wantsFreshCapture &&
            System.currentTimeMillis() - cachedSnapshot.capturedAtMs <= VISUAL_READ_CACHE_TTL_MS

        if (hasExplicitSaveIntent && cacheIsFresh && !isPersonFaceSaveIntent) {
            // Save directly instead of routing back through the root agent: feeding the cached
            // read back in as a replayed capture_current_view turn works (Gemini accepts it as
            // long as a user turn precedes the functionCall turn), but in testing the model kept
            // choosing to re-ask the confirm question as plain text instead of actually calling
            // save_life_memory, even when explicitly told not to -- so there's nothing to skip
            // the confirm gate FOR. Since the read is already on hand and the user has already
            // asked to save it, there's no judgment call left to make here.
            Log.d(TAG, "Explicit save intent + fresh cached read (${System.currentTimeMillis() - cachedSnapshot.capturedAtMs}ms old) -- saving directly: $text")
            saveVisualReadDirectly(userNote = text, aiInterpretation = cachedSnapshot.answer, businessCard = cachedSnapshot.businessCard)
            return
        }

        val contents = JSONArray()
        pruneStaleConversationHistory()
        for (turn in conversationHistory) {
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", turn.userText)))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", turn.answerText)))
            })
        }
        if (cacheIsFresh && !isPersonFaceSaveIntent) {
            // Any other follow-up about the same subject ("출력해줘", "전화번호가 뭐야", "다시
            // 말해줘"...) shouldn't force a brand new photo either -- replay the cached read as a
            // capture_current_view turn so the model answers from it directly. Not cleared here
            // (unlike the save path above) since a read-only follow-up doesn't consume the read;
            // more follow-ups within the same TTL window can keep reusing it. Excluded for
            // person-face saves -- replaying a stale document/scene read as capture_current_view
            // here would give the model something that looks like an already-finished read to
            // fall back on, defeating the system prompt's save_person(attach_current_photo=true)
            // rule and producing the same face-less save_life_memory outcome this whole guard
            // exists to prevent.
            Log.d(TAG, "Non-save follow-up + fresh cached read (${System.currentTimeMillis() - cachedSnapshot.capturedAtMs}ms old) -- replaying instead of recapturing: $text")
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", "현재 시야를 봐줘")))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().apply {
                    put("name", "capture_current_view")
                    put("args", JSONObject().put("reason", "이전에 읽은 내용 재사용"))
                })))
            })
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().apply {
                    put("name", "capture_current_view")
                    put("response", ToolResult.Success("[FINAL_ANSWER] ${cachedSnapshot.answer}").toJSON())
                })))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", text)))
        })

        repeat(MAX_ROOT_AGENT_STEPS) { stepIndex ->
            val step = stepWithRetry(contents, stepIndex)

            if (step == null) {
                // Root agent unreachable/unparseable. Live has no tools or rich prompt of its
                // own anymore, so there's nothing useful to hand it -- apologize instead.
                expectingReply = true
                geminiService.sendTextMessage(
                    TTS_ONLY_PREFIX + "지금 요청을 처리하는 데 문제가 있었어요. 다시 한번 말씀해주시겠어요?"
                )
                return
            }

            val call = step.functionCall
            if (call == null) {
                val answer = step.text
                if (answer.isNullOrBlank()) {
                    expectingReply = true
                    geminiService.sendTextMessage(
                        TTS_ONLY_PREFIX + "죄송해요, 답을 잘 못 만들었어요. 다시 한번 말씀해주시겠어요?"
                    )
                } else {
                    speakAndRemember(text, answer)
                }
                return
            }

            Log.d(TAG, "Root agent step $stepIndex -> tool ${call.name}, args=${call.args}")

            if (call.name in CONFIRM_REQUIRED_TOOLS && !hasExplicitSaveIntent) {
                pendingToolConfirmation = PendingToolConfirmation(call.name, call.args)
                val question = step.text?.takeIf { it.isNotBlank() } ?: "이 내용으로 저장할까요?"
                speakAndRemember(text, question)
                return
            }

            val result = dispatchRootAgentTool(call.name, call.args)

            // Same reasoning as the pre-loop fast path above: if the utterance already asked to
            // save and this step just read something (business card/document), don't hand the
            // "should I save this?" decision back to the model -- testing showed it often just
            // re-asks as plain text instead of ever calling save_life_memory. Save now instead.
            if (call.name == "capture_current_view" && hasExplicitSaveIntent) {
                val freshRead = cachedVisualRead
                if (freshRead != null) {
                    saveVisualReadDirectly(
                        userNote = text,
                        aiInterpretation = freshRead.answer,
                        businessCard = freshRead.businessCard,
                    )
                    return
                }
            }

            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().apply {
                    put("name", call.name)
                    put("args", JSONObject(call.args.filterValues { it != null }))
                })))
            })
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().apply {
                    put("name", call.name)
                    put("response", result.toJSON())
                })))
            })
        }

        Log.e(TAG, "Root agent exceeded $MAX_ROOT_AGENT_STEPS steps without finishing for: $text")
        geminiService.sendTextMessage(TTS_ONLY_PREFIX + "죄송해요, 지금 처리가 잘 안 되네요. 다시 한번 말씀해주시겠어요?")
    }

    private suspend fun dispatchRootAgentTool(name: String, args: Map<String, Any?>): ToolResult {
        // Any save_life_memory dispatch -- whether the model decided to call it itself (e.g. a
        // single "명함 인식하고 저장해줘" utterance) or the fast path below called it directly --
        // reuses a still-fresh cached capture_current_view frame instead of letting OpenClawBridge
        // trigger a second physical capturePhoto() on glasses just to attach an image it already
        // has. Single-use: cleared once consumed so a later unrelated save can't reuse stale art.
        val effectiveArgs = if (name == "save_life_memory") {
            val cached = cachedVisualRead
            if (cached != null && System.currentTimeMillis() - cached.capturedAtMs <= VISUAL_READ_CACHE_TTL_MS) {
                cachedVisualRead = null
                args + ("__preCapturedVisualFrame" to cached.frame)
            } else {
                args
            }
        } else {
            args
        }
        return if (name in ToolCallRouter.LOCAL_TOOL_NAMES) {
            handleLocalToolCall(GeminiFunctionCall(id = "root-agent", name = name, args = effectiveArgs))
        } else {
            openClawBridge.dispatch(name, effectiveArgs)
        }
    }

    /** Dispatches save_life_memory straight from a cached capture_current_view read -- see the
     * cache-reuse branch in runRootAgent() for why this bypasses another root agent step. When
     * the read was a business card, builds the person entity ourselves from the structured
     * fields Flash returned instead of leaving it to the model to re-derive from prose --
     * that's exactly where fields other than name/company were getting lost. */
    private suspend fun saveVisualReadDirectly(
        userNote: String,
        aiInterpretation: String,
        businessCard: GeminiFlashVisionClient.BusinessCard?,
    ) {
        val args = mutableMapOf<String, Any?>(
            "captured_at" to Instant.now().toString(),
            "user_note" to userNote,
            "ai_interpretation" to aiInterpretation,
        )
        if (businessCard != null) {
            args["labels"] = JSONArray().put("business_card").put("document")
            val entityMetadata = JSONObject().apply {
                put("org", businessCard.company)
                businessCard.role?.let { put("role", it) }
                businessCard.phone?.let { put("phone", it) }
                businessCard.email?.let { put("email", it) }
                businessCard.address?.let { put("address", it) }
            }
            args["entities"] = JSONArray().put(JSONObject().apply {
                put("type", "person")
                put("label", businessCard.name)
                put("metadata", entityMetadata)
            })
        }
        val result = dispatchRootAgentTool("save_life_memory", args)
        val spoken = try {
            GeminiRootAgentClient.synthesizeAnswer("save_life_memory", result.toJSON().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Synthesize after direct save failed: ${e.message}")
            null
        } ?: when (result) {
            is ToolResult.Success -> "저장했습니다."
            is ToolResult.Failure -> "저장하지 못했어요: ${result.error}"
        }
        speakAndRemember(userNote, spoken)
    }

    private fun confirmPendingToolCall() {
        val pending = pendingToolConfirmation ?: return
        pendingToolConfirmation = null
        viewModelScope.launch {
            val result = dispatchRootAgentTool(pending.name, pending.args)
            val spoken = try {
                GeminiRootAgentClient.synthesizeAnswer(pending.name, result.toJSON().toString())
            } catch (e: Exception) {
                Log.e(TAG, "Synthesize after confirm failed: ${e.message}")
                null
            } ?: when (result) {
                is ToolResult.Success -> "저장했습니다."
                is ToolResult.Failure -> "저장하지 못했어요: ${result.error}"
            }
            geminiService.sendTextMessage(TTS_ONLY_PREFIX + spoken)
        }
    }

    private fun cancelPendingToolCall() {
        pendingToolConfirmation = null
        geminiService.sendTextMessage(TTS_ONLY_PREFIX + "취소했습니다.")
    }

    private fun createSpeechInputController(): SpeechInputController {
        val context = AppContextProvider.require()
        val onPartial: (String) -> Unit = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = text,
                aiTranscript = "",
            )
        }
        val onFinal: (String) -> Unit = { text -> handleRecognizedSpeech(text) }
        val onError: (String) -> Unit = { message ->
            if (
                SettingsManager.speechRecognizerProvider == "whisper" &&
                !usingFallbackAndroidStt &&
                _uiState.value.isGeminiActive
            ) {
                usingFallbackAndroidStt = true
                commandSpeechRecognizer?.stop()
                commandSpeechRecognizer = KoreanSpeechRecognizer(
                    context = context,
                    onPartialText = onPartial,
                    onFinalText = onFinal,
                    onErrorText = { fallbackMessage ->
                        _uiState.value = _uiState.value.copy(errorMessage = fallbackMessage)
                    },
                ).also { it.start() }
                _uiState.value = _uiState.value.copy(
                    errorMessage = "$message Android STT로 전환합니다.",
                )
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = message)
            }
        }

        return if (SettingsManager.speechRecognizerProvider == "android" || usingFallbackAndroidStt) {
            KoreanSpeechRecognizer(context, onPartial, onFinal, onError)
        } else {
            WhisperSpeechRecognizer(
                context,
                onPartial,
                onFinal,
                onError,
                sttHintProvider = { contactActionManager.sttNameHint() },
            )
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
            usingFallbackAndroidStt = false
            pendingToolConfirmation = null
            expectingReply = false
            cachedVisualRead = null
            conversationHistory.clear()
            isProcessingUtterance = false
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
            "search_contacts" -> searchContacts(call)
            "call_contact" -> callContact(call)
            "text_contact" -> textContact(call)
            "send_email" -> sendEmail(call)
            "create_contact" -> createContact(call)
            "create_calendar_event" -> createCalendarEvent(call)
            else -> ToolResult.Failure("Unknown local tool: ${call.name}")
        }
    }

    private suspend fun searchContacts(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"]?.toString()?.trim().orEmpty()
        val topK = call.args["top_k"]?.toString()?.toIntOrNull() ?: 5
        return contactActionManager.searchContacts(query, topK)
    }

    private suspend fun callContact(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"]?.toString()?.trim().orEmpty()
        val phoneNumber = call.args["phone_number"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (query.isBlank() && phoneNumber == null) {
            return ToolResult.Failure("전화할 연락처 이름을 알려주세요.")
        }
        val action = PendingContactAction(
            type = PendingContactActionType.CALL,
            query = query,
            phoneNumber = phoneNumber,
        )
        setPendingContactAction(action)
        val target = query.ifBlank { phoneNumber.orEmpty() }
        return ToolResult.Success("${target}에게 전화하기 전 사용자 확인을 기다리고 있습니다. 앱 화면에서 실행을 누르거나 '응, 전화해'라고 말하면 실행됩니다.")
    }

    private suspend fun textContact(call: GeminiFunctionCall): ToolResult {
        val query = call.args["query"]?.toString()?.trim().orEmpty()
        val phoneNumber = call.args["phone_number"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val message = call.args["message"]?.toString()?.trim().orEmpty()
        if (query.isBlank() && phoneNumber == null) {
            return ToolResult.Failure("문자를 보낼 연락처 이름을 알려주세요.")
        }
        if (message.isBlank()) {
            return ToolResult.Failure("보낼 문자 내용을 함께 알려주세요.")
        }
        val action = PendingContactAction(
            type = PendingContactActionType.TEXT,
            query = query,
            message = message,
            phoneNumber = phoneNumber,
        )
        setPendingContactAction(action)
        val target = query.ifBlank { phoneNumber.orEmpty() }
        return ToolResult.Success("${target}에게 문자 보내기 전 사용자 확인을 기다리고 있습니다. 앱 화면에서 실행을 누르거나 '응, 보내'라고 말하면 실행됩니다.")
    }

    private fun setPendingContactAction(action: PendingContactAction) {
        val target = action.query.ifBlank { action.phoneNumber.orEmpty() }
        _uiState.value = _uiState.value.copy(
            pendingContactAction = action,
            aiTranscript = when (action.type) {
                PendingContactActionType.CALL -> "${target}에게 전화할까요?"
                PendingContactActionType.TEXT -> "${target}에게 아래 문자 내용을 보낼까요?\n${action.message.orEmpty()}"
            },
        )
    }

    fun confirmPendingContactAction() {
        val action = _uiState.value.pendingContactAction ?: return
        val phoneNumber = action.phoneNumber
        val result = if (phoneNumber != null) {
            when (action.type) {
                PendingContactActionType.CALL ->
                    contactActionManager.callNumber(phoneNumber, action.query.takeIf { it.isNotBlank() })
                PendingContactActionType.TEXT ->
                    contactActionManager.textNumber(phoneNumber, action.message.orEmpty(), action.query.takeIf { it.isNotBlank() })
            }
        } else {
            when (action.type) {
                PendingContactActionType.CALL -> contactActionManager.callContact(action.query)
                PendingContactActionType.TEXT -> contactActionManager.textContact(
                    query = action.query,
                    message = action.message.orEmpty(),
                )
            }
        }
        _uiState.value = _uiState.value.copy(
            pendingContactAction = null,
            aiTranscript = when (result) {
                is ToolResult.Success -> result.result
                is ToolResult.Failure -> result.error
            },
            errorMessage = (result as? ToolResult.Failure)?.error,
        )
    }

    fun cancelPendingContactAction() {
        _uiState.value = _uiState.value.copy(
            pendingContactAction = null,
            aiTranscript = "전화/문자 실행을 취소했습니다.",
        )
    }

    private fun isContactActionConfirm(text: String): Boolean {
        val normalized = text.replace(" ", "")
        // Shared by pendingContactAction (call/text) and pendingToolConfirmation (save_* tools)
        // -- covers both "응/전화해" style replies and "네/맞아/저장" style save confirmations.
        return listOf(
            "응", "네", "예", "확인", "그래", "맞아", "좋아", "진행", "실행",
            "저장", "보내", "보내줘", "전화해", "걸어", "걸어줘",
        ).any { normalized.contains(it) }
    }

    private fun isContactActionCancel(text: String): Boolean {
        val normalized = text.replace(" ", "")
        return listOf("취소", "아니", "하지마", "멈춰", "보내지마", "전화하지마", "걸지마")
            .any { normalized.contains(it) }
    }

    private suspend fun sendEmail(call: GeminiFunctionCall): ToolResult {
        val to = call.args["to"]?.toString()?.trim().orEmpty()
        val body = call.args["body"]?.toString()?.trim().orEmpty()
        if (to.isBlank()) {
            return ToolResult.Failure("받는 사람 이메일 주소를 알려주세요.")
        }
        if (body.isBlank()) {
            return ToolResult.Failure("메일 내용을 알려주세요.")
        }
        return contactActionManager.sendEmail(
            to = to,
            subject = call.args["subject"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            body = body,
        )
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

    /**
     * Every capture_current_view call -- regardless of how Live decided it needed one, so
     * business-card/object questions that don't match VisionQuestionDetector's keyword list
     * are covered too -- now goes entirely through the stateless Gemini 2.5 Flash judgment
     * (see GeminiFlashVisionClient / JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 1). Live no
     * longer sees the raw image at all here: Flash reads it, and its answer text (rule 17)
     * is the only thing handed back. Live's role is strictly TTS + extracting structured
     * save_life_memory fields FROM THAT TEXT -- it never re-judges the image itself, so it
     * can't drift from what Flash actually determined.
     */
    private suspend fun captureCurrentView(call: GeminiFunctionCall): ToolResult {
        val visualFrame = VisualMemoryFrameStore.captureFreshVisual()
            ?: return ToolResult.Failure(
                "No fresh camera image is available. Ask the user to hold still and retry."
            )

        val reason = call.args["reason"]?.toString()?.takeIf { it.isNotBlank() } ?: "current visual context"
        val flashResult = try {
            GeminiFlashVisionClient.answerVisionQuestion(question = reason, imageBase64 = visualFrame.base64)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Flash vision call failed (tool path): ${e.message}")
            null
        }

        Log.d(
            TAG,
            "capture_current_view -> Flash: ${visualFrame.source}, ${visualFrame.width}x${visualFrame.height}, ${visualFrame.jpegBytes} bytes, reason=$reason, canRead=${flashResult?.canRead}"
        )

        if (flashResult == null || flashResult.answer.isBlank()) {
            // Flash unreachable/unparseable -- fall back to attaching the raw image directly
            // so Live can still judge it rather than the turn going silent.
            geminiService.sendVideoFrameBase64(visualFrame.base64)
            visualContextSentForTurn = true
            // Let the ordered Live API image message arrive before the tool response tells the
            // model to answer. Without this, the model can answer from older conversation imagery.
            delay(350L)
            return ToolResult.Success(
                "Attached one fresh camera image from ${visualFrame.source} (${visualFrame.width}x${visualFrame.height}). " +
                    "Use the image to answer the user's visual question. reason=$reason"
            )
        }

        cachedVisualRead = CachedVisualRead(
            answer = flashResult.answer,
            capturedAtMs = System.currentTimeMillis(),
            frame = visualFrame,
            businessCard = flashResult.businessCard,
        )
        // Hand the root agent the structured fields too, not just prose -- if it ends up being
        // the one to call save_life_memory (see ROOT_AGENT_SYSTEM_INSTRUCTION), it should copy
        // these into the entity metadata verbatim instead of re-deriving them from a sentence.
        val structuredHint = flashResult.businessCard?.let { card ->
            val json = JSONObject().apply {
                put("type", "business_card")
                put("name", card.name)
                put("company", card.company)
                card.role?.let { put("role", it) }
                card.phone?.let { put("phone", it) }
                card.email?.let { put("email", it) }
                card.address?.let { put("address", it) }
            }
            "\n[구조화 데이터] $json"
        }.orEmpty()
        return ToolResult.Success("[FINAL_ANSWER] ${flashResult.answer}$structuredHint")
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
