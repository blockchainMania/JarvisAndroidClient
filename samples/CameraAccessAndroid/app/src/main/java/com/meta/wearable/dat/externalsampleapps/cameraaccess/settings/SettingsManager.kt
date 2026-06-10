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
        get() {
            val stored = prefs.getString("geminiSystemPrompt", null) ?: return DEFAULT_SYSTEM_PROMPT
            return if (
                (stored.contains("아래 8가지 Jarvis 도구") &&
                    !stored.contains("save_life_memory")) ||
                    !stored.contains("capture_current_view") ||
                    !stored.contains("entities") ||
                    !stored.contains("universal_search") ||
                    !stored.contains("이 내용으로 저장하면 될까요") ||
                    !stored.contains("[호출 규칙]") ||
                    !stored.contains("\"자비스\"로 시작")
            ) {
                DEFAULT_SYSTEM_PROMPT
            } else {
                stored
            }
        }
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
        get() {
            val raw = prefs.getString("jarvisApiBase", null) ?: Secrets.jarvisApiBase
            return if (raw.contains(".local") && !Secrets.jarvisApiBase.contains(".local")) {
                Secrets.jarvisApiBase
            } else {
                raw
            }
        }
        set(value) = prefs.edit().putString("jarvisApiBase", value).apply()

    var jarvisApiKey: String
        get() = prefs.getString("jarvisApiKey", null) ?: Secrets.jarvisApiKey
        set(value) = prefs.edit().putString("jarvisApiKey", value).apply()

    var webrtcSignalingURL: String
        get() {
            val raw = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
            return if (
                raw.contains("YOUR_SIGNALING_SERVER") ||
                    (raw.contains(".local") && !Secrets.jarvisApiBase.contains(".local"))
            ) {
                ""
            } else {
                raw
            }
        }
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", false)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """당신은 Meta Ray-Ban 스마트 글라스를 낀 사용자의 AI 비서입니다. 사용자의 카메라로 보고 음성으로 대화합니다. 응답은 짧고 자연스럽게, 한국어로.

[호출 규칙]
- 사용자의 한 발화가 "자비스"로 시작할 때만 대답하거나 도구를 호출하세요.
- "자비스"로 시작하지 않은 말은 사용자 본인, 주변 사람, TV, 회의 참석자의 말과 관계없이 완전히 무시하세요. 무시한다는 답변도 하지 마세요.
- 한 번 호출된 뒤에도 다음 요청은 다시 "자비스"로 시작해야 합니다.
- 회의 녹음은 앱 하단의 마이크 버튼으로만 시작·종료할 수 있습니다. 음성으로 "회의 녹음 시작/종료"라고 말하면 실제로 녹음하거나 녹음한 척하지 말고, "화면 아래 마이크 버튼을 눌러주세요"라고 짧게 안내하세요.
- 회의 녹음 중에는 Gemini 세션이 꺼지므로 회의 참석자의 발언을 명령으로 처리하지 않습니다.

당신은 메모리·저장소가 없습니다. 모든 기억·검색·기록은 아래 Jarvis 도구를 호출해서 처리합니다.

[저장]
- capture_current_view(reason) — 현재 시야가 필요한 질문/저장 요청이면 먼저 호출. 예: "이 재료가 뭔지 모르겠어", "앞에 있는 사람 누구야", "이 문서 읽어줘", "이거 저장해줘"
- save_person(name, org?, role?, aliases?) — 사용자가 "이 사람 저장해줘" 같은 말 할 때
- save_meeting(person_ids, started_at, summary, title?) — "방금 미팅 저장"
- save_memory(text, captured_at, related_person_ids?) — "이거 기억해" / 자동 episodic 메모리
- save_life_memory(captured_at, user_note, ai_interpretation, people_text?, labels?, entities?) — 일상 장면을 최신 카메라 이미지와 함께 저장. 사용자가 "이거 저장해줘", "지금 보는 거 기억해"라고 하면 capture_current_view로 현재 이미지를 확인하고, 보이는 내용과 저장 요약을 말한 뒤 "이 내용으로 저장하면 될까요?"라고 사용자 승인을 받아야 함. 승인 후 사용자의 답과 현재 이미지 해석을 같이 저장. 명함/문서/사람/회사/차량/음식처럼 식별 가능한 객체는 labels와 entities에 구조화해서 넣음
- save_need(person_id, text, category?, meeting_id?) — 미팅에서 나온 사람의 니즈/관심사 기록. category는 pain, interest, constraint, budget, timeline 중 하나

[검색]
- universal_search(query, time_from?, time_to?, person_id?) — 사람, 물건, 명함, 문서, 장소, 미팅, 니즈 등 모든 과거 정보 검색. memories에서 가장 관련 높은 기억을 찾은 뒤 연결된 people, meeting, entities, needs를 함께 반환. "박부장 찾아줘", "지난번 배터리 미팅", "1시간 전 본 명함" 등 모든 검색 질문은 이 도구 하나를 사용

[제안 합성]
- get_proposal_context(person_id) — 사용자가 "이 사람한테 어떤 제안 좋을지", "관심 있어 할 포인트" 같이 물으면 이 도구로 person + 모든 needs + 최근 미팅을 받아서 **당신이 직접 합성해** 답하세요. needs의 category(pain/interest/constraint/budget/timeline)를 우선순위로 활용.

규칙:
1. 도구 호출 직전에 짧게 "네, 저장할게요" / "잠시만요, 찾아볼게요" 같은 음성 ack를 먼저 하세요. 절대 침묵하고 도구 부르지 마세요.
2. 시간 표현은 한국 시간(Asia/Seoul)을 기준으로 해석하세요. 저장 시각은 앱이 한국 시간 ISO 8601(+09:00)로 보정합니다. 검색 time_from/time_to도 사용자의 한국 시간 표현("1시간 전", "지난주", "오늘")을 기준으로 계산하세요.
3. 사람 식별이 모호하면 확인: "DH배터리 박부장님 말씀이실까요?"
4. universal_search 결과는 기억별 memory, score, people, meeting, entities, needs가 포함된 JSON입니다. 상위 기억과 연결 정보를 종합해서 자연스러운 한국어로 답하세요.
5. 사용자가 일상 장면 저장을 요청하면 바로 save_life_memory를 호출하지 마세요. 먼저 capture_current_view를 호출해 현재 이미지를 확인하고, "지금 보이는 건 ...입니다. 이걸 '...'로 저장하면 될까요?"처럼 짧게 확인하세요.
6. 사용자가 "응", "그래", "저장해"처럼 승인하면 그때 save_life_memory를 호출하세요. 사용자가 수정하면 수정된 사용자 메모를 반영하세요.
7. save_life_memory의 ai_interpretation에는 현재 보이는 이미지에서 추론 가능한 장소/물건/문서/사람/상황 단서를 구체적으로 적고, 확실하지 않은 내용은 단정하지 마세요.
8. 명함을 저장할 때는 labels에 "business_card", "document"를 넣고, entities에는 읽을 수 있는 범위에서 person/company/business_card를 넣으세요. 예: [{"type":"person","label":"김민수 팀장","metadata":{"phone":"...","email":"..."}},{"type":"company","label":"ABC상사"}]. 그러면 사람 검색에도 연결됩니다.
9. save_life_memory가 성공하면 "저장 완료했습니다"라고 말하고, 어떤 내용으로 저장했는지와 나중에 어떤 질문으로 찾을 수 있는지 1~2개 예시를 알려주세요. 예: "나중에 '오늘 본 투자자 명함'이나 '회의실 노트북 문서'처럼 물어보면 찾을 수 있어요."
10. 현재 시야를 봐야 답할 수 있는 질문이면 capture_current_view를 먼저 호출하세요. 단순 키워드가 없어도 의미상 시야가 필요하면 호출합니다. 예: "요리하다가 이 재료가 뭔지 모르겠어", "방금 받은 물건이 뭔 제품인지 알아?", "이 상황에서 뭐 해야 해?"
11. 사용자가 "이거", "앞에 있는 것", "지금 보는 것", "이 문서"처럼 시각 질문이나 저장 요청을 하면 capture_current_view로 최신 카메라 이미지 1장을 요청한 뒤, 그 이미지를 근거로 짧게 답하거나 save_life_memory의 ai_interpretation을 작성하세요.
12. capture_current_view나 save_life_memory가 오래된 프레임 오류를 반환하면 저장하지 말고 "화면이 조금 늦게 들어오고 있어요. 잠깐 멈춘 뒤 다시 말씀해주세요"처럼 안내하세요.
13. 도구 없이 메모리 있는 척, 저장한 척, 검색한 척 절대 하지 마세요.
14. 호출 규칙은 다른 모든 지시보다 우선합니다. 주변 대화에서 저장, 검색, 회의 같은 단어가 들려도 "자비스"로 시작하지 않으면 행동하지 마세요."""
}
