package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var geminiAPIKey: String
        get() = prefs.getString("geminiAPIKey", null) ?: Secrets.geminiAPIKey
        set(value) = prefs.edit().putString("geminiAPIKey", value).apply()

    var geminiSystemPrompt: String
        get() = prefs.getString("geminiSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("geminiSystemPrompt", value).apply()

    var openClawHost: String
        get() = prefs.getString("openClawHost", null) ?: Secrets.openClawHost
        set(value) = prefs.edit().putString("openClawHost", value).apply()

    var openClawPort: Int
        get() {
            val stored = prefs.getInt("openClawPort", 0)
            return if (stored != 0) stored else Secrets.openClawPort
        }
        set(value) = prefs.edit().putInt("openClawPort", value).apply()

    var openClawHookToken: String
        get() = prefs.getString("openClawHookToken", null) ?: Secrets.openClawHookToken
        set(value) = prefs.edit().putString("openClawHookToken", value).apply()

    var openClawGatewayToken: String
        get() = prefs.getString("openClawGatewayToken", null) ?: Secrets.openClawGatewayToken
        set(value) = prefs.edit().putString("openClawGatewayToken", value).apply()

    var jarvisApiBase: String
        get() = prefs.getString("jarvisApiBase", null) ?: Secrets.jarvisApiBase
        set(value) = prefs.edit().putString("jarvisApiBase", value).apply()

    var jarvisApiKey: String
        get() = prefs.getString("jarvisApiKey", null) ?: Secrets.jarvisApiKey
        set(value) = prefs.edit().putString("jarvisApiKey", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", true)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """당신은 Meta Ray-Ban 스마트 글라스를 낀 사용자의 AI 비서입니다. 사용자의 카메라로 보고 음성으로 대화합니다. 응답은 짧고 자연스럽게, 한국어로.

당신은 메모리·저장소가 없습니다. 모든 기억·검색·기록은 아래 8가지 Jarvis 도구를 호출해서 처리합니다.

[저장]
- save_person(name, org?, role?, aliases?) — 사용자가 "이 사람 저장해줘" 같은 말 할 때
- save_meeting(person_ids, started_at, summary, title?) — "방금 미팅 저장"
- save_memory(text, captured_at, related_person_ids?) — "이거 기억해" / 자동 episodic 메모리
- save_need(person_id, text, category?, meeting_id?) — 미팅에서 나온 사람의 니즈/관심사 기록. category는 pain, interest, constraint, budget, timeline 중 하나

[검색]
- search_people(query) — 이름·별칭·회사로 사람 검색 (예: "박부장 찾아줘")
- search_meetings(query, time_from?, time_to?) — 미팅 의미 검색 (예: "지난번 배터리 부품사 미팅")
- search_memory(query, time_from?, time_to?, person_id?) — episodic 메모리 검색 (예: "1시간 전 본 거", "지난주 만난 사람")

[제안 합성]
- get_proposal_context(person_id) — 사용자가 "이 사람한테 어떤 제안 좋을지", "관심 있어 할 포인트" 같이 물으면 이 도구로 person + 모든 needs + 최근 미팅을 받아서 **당신이 직접 합성해** 답하세요. needs의 category(pain/interest/constraint/budget/timeline)를 우선순위로 활용.

규칙:
1. 도구 호출 직전에 짧게 "네, 저장할게요" / "잠시만요, 찾아볼게요" 같은 음성 ack를 먼저 하세요. 절대 침묵하고 도구 부르지 마세요.
2. 시간 표현은 ISO 8601 UTC로 변환: "1시간 전" → 현재시각 - 1h를 time_from에. "지난주" → 7일 범위.
3. 사람 식별이 모호하면 확인: "DH배터리 박부장님 말씀이실까요?"
4. 도구 결과는 JSON 문자열입니다. 그 안의 필드(name, summary, text, category 등)를 자연스러운 한국어 문장으로 변환해 말하세요.
5. 도구 없이 메모리 있는 척, 저장한 척, 검색한 척 절대 하지 마세요."""
}
