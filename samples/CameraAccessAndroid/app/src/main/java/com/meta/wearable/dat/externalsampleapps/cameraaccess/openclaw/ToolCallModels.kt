package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import org.json.JSONArray
import org.json.JSONObject

// ─── Gemini Tool Call (parsed from server JSON) ──────────────────

data class GeminiFunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

data class GeminiToolCall(
    val functionCalls: List<GeminiFunctionCall>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCall? {
            val toolCall = json.optJSONObject("toolCall") ?: return null
            val calls = toolCall.optJSONArray("functionCalls") ?: return null
            val functionCalls = mutableListOf<GeminiFunctionCall>()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id", "")
                val name = call.optString("name", "")
                if (id.isEmpty() || name.isEmpty()) continue
                val argsObj = call.optJSONObject("args")
                val args = mutableMapOf<String, Any?>()
                if (argsObj != null) {
                    for (key in argsObj.keys()) {
                        args[key] = argsObj.opt(key)
                    }
                }
                functionCalls.add(GeminiFunctionCall(id, name, args))
            }
            return if (functionCalls.isNotEmpty()) GeminiToolCall(functionCalls) else null
        }
    }
}

// ─── Gemini Tool Call Cancellation ───────────────────────────────

data class GeminiToolCallCancellation(
    val ids: List<String>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCallCancellation? {
            val cancellation = json.optJSONObject("toolCallCancellation") ?: return null
            val idsArray = cancellation.optJSONArray("ids") ?: return null
            val ids = mutableListOf<String>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getString(i))
            }
            return if (ids.isNotEmpty()) GeminiToolCallCancellation(ids) else null
        }
    }
}

// ─── Tool Result ─────────────────────────────────────────────────

sealed class ToolResult {
    data class Success(val result: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()

    fun toJSON(): JSONObject = when (this) {
        is Success -> JSONObject().put("result", result)
        is Failure -> JSONObject().put("error", error)
    }
}

// ─── Tool Call Status (for UI) ──────────────────────────────────

sealed class ToolCallStatus {
    data object Idle : ToolCallStatus()
    data class Executing(val name: String) : ToolCallStatus()
    data class Completed(val name: String) : ToolCallStatus()
    data class Failed(val name: String, val error: String) : ToolCallStatus()
    data class Cancelled(val name: String) : ToolCallStatus()

    val displayText: String
        get() = when (this) {
            is Idle -> ""
            is Executing -> "Running: $name..."
            is Completed -> "Done: $name"
            is Failed -> "Failed: $name - $error"
            is Cancelled -> "Cancelled: $name"
        }

    val isActive: Boolean
        get() = this is Executing
}

// ─── Connection State (Jarvis API, name kept for compatibility) ──

sealed class OpenClawConnectionState {
    data object NotConfigured : OpenClawConnectionState()
    data object Checking : OpenClawConnectionState()
    data object Connected : OpenClawConnectionState()
    data class Unreachable(val message: String) : OpenClawConnectionState()
}

// ─── Jarvis Tool Declarations (sent to Gemini at session setup) ──
//
// Typed functions that map 1:1 to Jarvis Memory API endpoints.
// Type name `ToolDeclarations` is preserved for wiring compatibility.

object ToolDeclarations {
    fun allDeclarationsJSON(): JSONArray = JSONArray()
        .put(captureCurrentView())
        .put(startRecording())
        .put(stopRecording())
        .put(savePerson())
        .put(searchPeople())
        .put(saveMeeting())
        .put(searchMeetings())
        .put(saveMemory())
        .put(saveLifeMemory())
        .put(searchMemory())
        .put(saveNeed())
        .put(getProposalContext())

    // ── helpers ───────────────────────────────────────────────────
    private fun strProp(desc: String) = JSONObject()
        .put("type", "string")
        .put("description", desc)

    private fun intProp(desc: String) = JSONObject()
        .put("type", "integer")
        .put("description", desc)

    private fun arrStrProp(desc: String) = JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))
        .put("description", desc)

    private fun entityArrayProp(desc: String) = JSONObject()
        .put("type", "array")
        .put("description", desc)
        .put("items", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject()
                .put("type", enumProp(
                    "객체 타입",
                    listOf("person", "company", "object", "place", "document", "business_card", "vehicle", "food"),
                ))
                .put("label", strProp("객체 이름/표시명. 예: '김민수 팀장', 'ABC상사', '빨간 자동차'"))
                .put("aliases", arrStrProp("별칭 목록 (선택)"))
                .put("metadata", JSONObject()
                    .put("type", "object")
                    .put("description", "명함의 전화번호/email/직책 등 추가 JSON 정보"))
            )
            put("required", JSONArray(listOf("type", "label")))
        })

    private fun enumProp(desc: String, values: List<String>) = JSONObject()
        .put("type", "string")
        .put("description", desc)
        .put("enum", JSONArray(values))

    private fun decl(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(required))
        })
        put("behavior", "BLOCKING")
    }

    // ── declarations ──────────────────────────────────────────────
    private fun captureCurrentView() = decl(
        name = "capture_current_view",
        description = "현재 사용자가 보고 있는 장면이 필요할 때 호출. 예: '이 재료가 뭔지 모르겠어', '앞에 있는 사람 누구야?', '이 문서 읽어줘', '이거 저장해줘'. 앱은 최신 카메라 프레임 1장을 현재 대화에 첨부합니다. 비디오를 계속 보내지 말고, 현재 시야가 필요할 때만 이 도구를 호출하세요.",
        properties = JSONObject()
            .put("reason", strProp("현재 시야가 필요한 이유. 예: '재료 식별', '명함 읽기', '장면 저장 전 해석'")),
        required = listOf("reason"),
    )

    private fun startRecording() = decl(
        name = "start_recording",
        description = "사용자가 '녹음 시작해줘', '회의 기록 시작', '지금부터 받아 적어줘'라고 하면 호출. 앱은 이후 사용자 발화 transcript를 모읍니다.",
        properties = JSONObject()
            .put("title", strProp("녹음/회의 제목 (선택)")),
        required = emptyList(),
    )

    private fun stopRecording() = decl(
        name = "stop_recording",
        description = "사용자가 '녹음 끝내줘', '회의 기록 종료', '요약해줘'라고 하면 호출. 앱은 녹음 구간 transcript를 반환하므로, 그 내용을 한국어로 요약하고 할일/결정사항을 알려주세요.",
        properties = JSONObject()
            .put("save", JSONObject()
                .put("type", "boolean")
                .put("description", "결과를 메모리/미팅으로 저장해야 하면 true")),
        required = emptyList(),
    )

    private fun savePerson() = decl(
        name = "save_person",
        description = "사용자가 새 사람을 메모리에 저장하라고 할 때. 예: '이 사람 저장해줘', '방금 만난 박부장 등록'.",
        properties = JSONObject()
            .put("name", strProp("사람 이름 (한국어/영어)"))
            .put("aliases", arrStrProp("별칭/닉네임 목록 (선택)"))
            .put("org", strProp("소속 회사/조직 (선택)"))
            .put("role", strProp("직책/역할 (선택)"))
            .put("notes_summary", strProp("간단한 요약/메모 (선택)")),
        required = listOf("name"),
    )

    private fun searchPeople() = decl(
        name = "search_people",
        description = "이름·별칭·회사명으로 사람을 찾을 때. 예: '박부장 누구지', 'DH배터리 사람'.",
        properties = JSONObject()
            .put("query", strProp("검색어 (이름·별칭·회사)"))
            .put("top_k", intProp("반환할 최대 개수 (기본 5)")),
        required = listOf("query"),
    )

    private fun saveMeeting() = decl(
        name = "save_meeting",
        description = "방금 끝난 또는 과거의 미팅을 기록할 때. summary는 미팅에서 나온 핵심 논의를 한두 문장으로.",
        properties = JSONObject()
            .put("title", strProp("미팅 제목 (선택)"))
            .put("person_ids", arrStrProp("참석자 person UUID 목록"))
            .put("started_at", strProp("미팅 시작 시각 ISO 8601 UTC, 예: 2026-05-30T10:00:00Z"))
            .put("ended_at", strProp("미팅 종료 시각 ISO 8601 UTC (선택)"))
            .put("location", strProp("장소 (선택)"))
            .put("summary", strProp("미팅 요약 (의미 검색에 쓰임)"))
            .put("raw_transcript", strProp("발화 원문 (선택, 길어도 OK)")),
        required = listOf("person_ids", "started_at", "summary"),
    )

    private fun searchMeetings() = decl(
        name = "search_meetings",
        description = "미팅을 의미 기반으로 검색. 예: '지난번 배터리 부품사 미팅', '안전성 관련 미팅'.",
        properties = JSONObject()
            .put("query", strProp("자연어 검색 쿼리"))
            .put("top_k", intProp("반환할 최대 개수 (기본 5)"))
            .put("time_from", strProp("시작 시간 (ISO 8601 UTC, 선택)"))
            .put("time_to", strProp("끝 시간 (ISO 8601 UTC, 선택)"))
            .put("person_id", strProp("특정 person UUID로 필터 (선택)")),
        required = listOf("query"),
    )

    private fun saveMemory() = decl(
        name = "save_memory",
        description = "임의의 사실·관찰·발화를 episodic 메모리에 저장. 예: '이거 기억해', '방금 본 책 제목 메모'. source는 camera/voice/manual/derived 중 하나.",
        properties = JSONObject()
            .put("text", strProp("저장할 내용"))
            .put("captured_at", strProp("관측 시각 ISO 8601 UTC"))
            .put("related_person_ids", arrStrProp("관련된 person UUID 목록 (선택)"))
            .put("related_meeting_id", strProp("관련 미팅 UUID (선택)"))
            .put("source", enumProp("출처", listOf("camera", "voice", "manual", "derived"))),
        required = listOf("text", "captured_at"),
    )

    private fun saveLifeMemory() = decl(
        name = "save_life_memory",
        description = "일상 장면을 이미지와 함께 저장. 저장 전에는 반드시 capture_current_view로 현재 장면을 확인하고, 보이는 내용을 설명한 뒤 '이 내용으로 저장하면 될까요?'라고 사용자 확인을 받으세요. 사용자가 승인하면 현재 장면에 대한 AI 해석과 사용자 메모를 함께 저장하세요. 이미지는 앱이 최신 카메라 프레임을 자동 첨부합니다.",
        properties = JSONObject()
            .put("captured_at", strProp("관측 시각 ISO 8601 UTC"))
            .put("user_note", strProp("사용자가 저장하고 싶다고 말한 핵심 정보"))
            .put("ai_interpretation", strProp("현재 이미지/상황을 AI가 해석한 설명. 장소, 물건, 사람, 맥락, 중요한 단서를 포함"))
            .put("people_text", strProp("사용자가 말한 관련 사람 정보. 예: '김민수 팀장, 어제 미팅에서 만남' (선택)"))
            .put("labels", arrStrProp("장면 라벨. 예: business_card, document, vehicle, food, meeting_note"))
            .put("entities", entityArrayProp("이미지와 사용자 메모에서 식별한 객체/사람/회사/문서 목록. 명함이면 person/company/business_card를 추출"))
            .put("related_person_ids", arrStrProp("이미 알고 있는 관련 person UUID 목록 (선택)"))
            .put("source", enumProp("출처", listOf("camera", "voice", "manual", "derived"))),
        required = listOf("captured_at", "user_note", "ai_interpretation"),
    )

    private fun searchMemory() = decl(
        name = "search_memory",
        description = "episodic 메모리를 의미·시간·인물로 검색. 예: '1시간 전 본 거', '지난주 만난 사람들과의 대화'.",
        properties = JSONObject()
            .put("query", strProp("자연어 검색 쿼리"))
            .put("top_k", intProp("반환할 최대 개수 (기본 5)"))
            .put("time_from", strProp("시작 시간 (ISO 8601 UTC, 선택)"))
            .put("time_to", strProp("끝 시간 (ISO 8601 UTC, 선택)"))
            .put("person_id", strProp("특정 person UUID로 필터 (선택)")),
        required = listOf("query"),
    )

    private fun saveNeed() = decl(
        name = "save_need",
        description = "사람의 니즈·관심사·제약을 기록. 미팅에서 추출한 신호를 저장할 때 사용.",
        properties = JSONObject()
            .put("person_id", strProp("주인공 person UUID"))
            .put("meeting_id", strProp("출처 meeting UUID (선택)"))
            .put("text", strProp("니즈 내용. 예: '안전성 인증서 요구'"))
            .put("category", enumProp(
                "카테고리",
                listOf("pain", "interest", "constraint", "budget", "timeline"),
            ))
            .put("confidence", JSONObject()
                .put("type", "number")
                .put("description", "0.0~1.0 확신도 (선택, 기본 1.0)")),
        required = listOf("person_id", "text"),
    )

    private fun getProposalContext() = decl(
        name = "get_proposal_context",
        description = "특정 사람을 위한 제안 합성용 컨텍스트(person + needs + 최근 미팅)를 가져옴. 사용자가 '이 사람한테 어떤 제안 좋을지', '관심 있어 할 포인트' 같이 물을 때. 결과를 받아 직접 한국어로 합성해 답하세요.",
        properties = JSONObject()
            .put("person_id", strProp("대상 person UUID")),
        required = listOf("person_id"),
    )
}
