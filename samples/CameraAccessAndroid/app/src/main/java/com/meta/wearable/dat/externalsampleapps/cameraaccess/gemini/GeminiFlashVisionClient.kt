package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Stateless single-shot Gemini 2.5 Flash calls for anything that needs the current camera
 * view judged (vision questions like "what's this", and the capture_current_view tool call
 * Gemini Live makes on its own). Kept separate from GeminiLiveService (the streaming audio
 * session) so the image+question round trip is a plain request/response with no ordering
 * dependency on session state -- see JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md 3.3.
 *
 * Routed through the Jarvis backend's /agent/flash/generate proxy rather than calling Google
 * directly -- the real Gemini API key never leaves the server, and every request/response is
 * logged there so debugging doesn't require adb access to the phone. This class still owns the
 * system prompt/schema/parsing; the backend is a schema-agnostic passthrough.
 */
object GeminiFlashVisionClient {
    private const val TAG = "GeminiFlashVision"

    data class VisionAnswer(val canRead: Boolean, val answer: String)

    // Detailed (field-by-field) answers are long enough that reading them aloud over Gemini
    // Live has tripped the WebSocket's ping/pong keepalive mid-sentence in testing. Only ask
    // for that level of detail when the request actually looks save/entity-extraction related;
    // a plain "what's this" question gets a short conversational answer instead.
    private val SAVE_INTENT_KEYWORDS = listOf("저장", "기억해", "등록", "기록")

    private fun systemInstruction(detailed: Boolean): String {
        val base = "당신은 스마트 글라스를 쓴 사용자의 카메라 화면을 판독하는 비전 분석 담당입니다. 이 판독 결과는 " +
            "이후 다른 AI(Gemini Live)가 이미지 없이 텍스트만 보고 그대로 사용자에게 말해줍니다. 첨부된 이미지만 " +
            "근거로 판단하세요.\n" +
            "질문에 필요한 내용이 이미지에서 선명하게 보이면 can_read를 true로 하세요.\n"
        val body = if (detailed) {
            "명함이면 다음 6개 항목을 이 순서로 확인하세요: 이름, 회사명, 직책, 전화번호, 이메일주소, 회사주소. " +
                "나중에 이 문장만 보고도 각 항목을 구분해서 추출할 수 있도록 answer 안에 항목별로 명확히 쓰세요. " +
                "예: \"이름은 김민수 팀장, 회사는 ABC상사, 전화번호는 010-1234-5678, 이메일은 kim@abc.com, " +
                "회사 주소는 서울시 강남구 테헤란로 123입니다.\" 명함이 아닌 다른 문서/장면이면 이 6항목 형식에 " +
                "얽매이지 말고 있는 정보를 항목별로 명확히 쓰세요. 이미지에서 실제로 보이지 않는 항목은 " +
                "언급하지 말고 통째로 생략하세요 -- \"전화번호는 안 보여요\" 같은 문구도 넣지 말고 그냥 빼세요 " +
                "(지어내지 마세요).\n"
        } else {
            "answer에는 짧고 자연스러운 한국어 구어체로 1~2문장만 쓰세요. 명함이나 문서여도 무엇인지와 이름 " +
                "정도만 간단히 언급하고, 모든 항목(주소·전화번호·이메일 등)을 나열하지 마세요. 사용자가 저장을 " +
                "요청한 게 아니라 단순히 궁금해서 물어본 것입니다.\n"
        }
        val tail = "글씨가 흐릿하거나 잘려서, 또는 각도/거리 때문에 확실히 읽을 수 없으면 절대로 그럴듯하게 " +
            "추측하거나 지어내지 마세요. 이 경우 can_read를 false로 하고, answer에는 사용자에게 다시 비춰달라고 " +
            "요청하는 짧은 한국어 안내 문장 하나만 쓰세요. 예: \"조금 더 가까이, 밝은 곳에서 다시 비춰주시겠어요?\""
        return base + body + tail
    }

    private val RESPONSE_SCHEMA = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("can_read", JSONObject().put("type", "BOOLEAN"))
            put("answer", JSONObject().put("type", "STRING"))
        })
        put("required", JSONArray().put("can_read").put("answer"))
    }

    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun answerVisionQuestion(question: String, imageBase64: String): VisionAnswer? {
        if (!GeminiConfig.isJarvisConfigured) return null

        val detailed = SAVE_INTENT_KEYWORDS.any { question.contains(it) }
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction(detailed))))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", imageBase64)
                        })
                    })
                    put(JSONObject().put("text", question))
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                // 256 was too tight -- longer answers (e.g. reading back a full business
                // card) got cut off mid-string, producing unparseable truncated JSON.
                put("maxOutputTokens", 1024)
                put("responseMimeType", "application/json")
                put("responseSchema", RESPONSE_SCHEMA)
            })
        }

        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase}/agent/flash/generate")
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        val responseBody = try {
            executeSuspend(request)
        } catch (e: Exception) {
            Log.e(TAG, "Flash proxy request failed: ${e.message}")
            return null
        }
        return responseBody?.let { parseAnswer(it) }
    }

    private suspend fun executeSuspend(request: Request): String? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IOException("Gemini Flash HTTP ${it.code}: ${text?.take(300)}")
                                )
                            }
                            return
                        }
                        if (cont.isActive) cont.resume(text)
                    }
                }
            })
        }

    private fun parseAnswer(rawBody: String): VisionAnswer? {
        try {
            val json = JSONObject(rawBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.e(TAG, "No candidates in Flash response: ${rawBody.take(500)}")
                return null
            }
            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.optString("finishReason", "")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts == null) {
                Log.e(TAG, "No content parts in Flash response (finishReason=$finishReason): ${rawBody.take(500)}")
                return null
            }
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            val raw = sb.toString().trim()
            // responseSchema is supposed to force pure JSON, but the model occasionally
            // wraps it in prose or a markdown fence -- pull out the {...} span defensively
            // instead of trusting the whole string is valid JSON on its own.
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                Log.e(TAG, "No JSON object found in Flash text (finishReason=$finishReason): ${raw.take(500)}")
                return null
            }
            val structured = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val answer = structured.optString("answer", "").trim()
            if (answer.isBlank()) return null
            return VisionAnswer(canRead = structured.optBoolean("can_read", true), answer = answer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini Flash response: ${e.message}, raw=${rawBody.take(500)}")
            return null
        }
    }
}
