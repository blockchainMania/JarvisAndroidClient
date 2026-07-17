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

    // Live is TTS-only now (GeminiRootAgentClient makes all tool-call decisions instead --
    // see JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2), so the old staleness-detection
    // heuristic (checking for tool-name substrings from the previous 17-rule prompt) is
    // moot: DEFAULT_SYSTEM_PROMPT is tiny now and has nothing to progressively migrate.
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
        get() {
            val raw = prefs.getString("jarvisApiBase", null) ?: Secrets.jarvisApiBase
            return raw.trim().trimEnd('/')
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

    var speechRecognizerProvider: String
        get() {
            val stored = prefs.getString("speechRecognizerProvider", null)
            // Whisper (ggml-tiny) hangs for tens of seconds on some devices even with
            // no prompt at all, so Android STT stays the default; Whisper remains an
            // explicit opt-in from Settings for devices where it does run fine.
            return if (stored == "whisper") "whisper" else "android"
        }
        set(value) = prefs.edit().putString("speechRecognizerProvider", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    // Live no longer decides anything -- GeminiRootAgentClient (Gemini 2.5 Flash, stateless)
    // makes every tool-call/routing decision now and hands Live only finished text to speak.
    // See JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2. Keep this prompt small and literal;
    // it is not a place for tool knowledge or judgment rules anymore.
    const val DEFAULT_SYSTEM_PROMPT = """당신은 사용자에게 미리 준비된 답변을 소리 내어 전달하는 음성 담당입니다. 판단이나 도구 호출은 절대 하지 않습니다.

전달받은 텍스트를 자연스러운 한국어 구어체로, 짧고 명확하게 그대로 읽어주세요. 내용을 바꾸거나 새로 추론해서 덧붙이거나 생략하지 마세요. 사용자가 무슨 말을 하든 도구를 호출하려 하지 말고, 방금 전달받은 텍스트를 읽는 것에만 집중하세요."""
}
