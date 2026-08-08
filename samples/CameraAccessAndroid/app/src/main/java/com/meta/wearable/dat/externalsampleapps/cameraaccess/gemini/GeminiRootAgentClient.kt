package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolDeclarations
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
 * Stateless root-agent decision step (Gemini 2.5 Flash, plain generateContent -- not a Live
 * session). Given the running `contents` turn history, returns EITHER a function call to
 * dispatch OR a finished text answer. The caller (GeminiSessionViewModel) owns the loop:
 * append the call/response turns and call step() again, or stop on a text answer. Gemini
 * Live never makes this decision anymore -- it only speaks whatever text this produces.
 * See JARVIS_ROOT_AGENT_ARCHITECTURE_KO.md Phase 2.
 *
 * Routed through the Jarvis backend's /agent/flash/generate proxy rather than calling Google
 * directly -- the real Gemini API key never leaves the server, and every decision step is
 * logged there so debugging doesn't require adb access to the phone. This class still owns the
 * tool declarations and drives the loop; the backend is a schema-agnostic passthrough.
 */
object GeminiRootAgentClient {
    private const val TAG = "GeminiRootAgent"

    data class FunctionCallStep(val name: String, val args: Map<String, Any?>)
    data class RootAgentStep(val functionCall: FunctionCallStep?, val text: String?)

    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Live's "behavior": "BLOCKING" hint is meaningless for a stateless REST call -- strip it.
    private val TOOLS: JSONArray by lazy {
        val decls = ToolDeclarations.allDeclarationsJSON()
        val stripped = JSONArray()
        for (i in 0 until decls.length()) {
            val d = JSONObject(decls.getJSONObject(i).toString())
            d.remove("behavior")
            stripped.put(d)
        }
        stripped
    }

    suspend fun step(contents: JSONArray, systemInstruction: String): RootAgentStep? {
        if (!GeminiConfig.isJarvisConfigured) return null

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("contents", contents)
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", TOOLS)))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                // Verified via curl: with unbounded/default thinking, this exact combination
                // (long system instruction + many tool declarations) reliably produced a
                // completely empty response -- finishReason STOP, zero output tokens, not even
                // truncation -- specifically for queries that should trigger universal_search
                // (e.g. "OO 검색해줘", "OO한테 전화해줘"). Capping thinkingBudget eliminated it
                // in every repro case at the time. The system prompt and tool count have both
                // grown substantially since (send_email, get_proposal_context chaining, the
                // identify_person score-threshold rule, ...), and api.err logs show the same
                // empty-response quirk recurring under the heavier reasoning load of an
                // identify_person multi-face-error retry -- bumped both values for headroom.
                // stepWithRetry() also now retries on an empty (not just exceptioning) step, as
                // a second line of defense since no fixed budget can rule this out entirely.
                put("maxOutputTokens", 3072)
                put("thinkingConfig", JSONObject().put("thinkingBudget", 1536))
            })
        }
        return call(body)
    }

    /** One-shot: turn a raw tool result into a short spoken Korean answer. No tools involved. */
    suspend fun synthesizeAnswer(toolName: String, resultJson: String): String? {
        if (!GeminiConfig.isJarvisConfigured) return null

        val prompt = "도구 \"$toolName\" 실행 결과: $resultJson\n\n" +
            "이 결과를 바탕으로 사용자에게 들려줄 짧고 자연스러운 한국어 구어체 답변 1~2문장만 쓰세요. " +
            "설명이나 JSON 없이 답변 문장만 쓰세요."
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 256)
            })
        }
        return call(body)?.text
    }

    private suspend fun call(body: JSONObject): RootAgentStep? {
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase}/agent/flash/generate")
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        val responseBody = try {
            executeSuspend(request)
        } catch (e: Exception) {
            Log.e(TAG, "Root agent request failed: ${e.message}")
            return null
        }
        return responseBody?.let { parseStep(it) }
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
                                    IOException("Root agent HTTP ${it.code}: ${text?.take(300)}")
                                )
                            }
                            return
                        }
                        if (cont.isActive) cont.resume(text)
                    }
                }
            })
        }

    private fun parseStep(rawBody: String): RootAgentStep? {
        return try {
            val json = JSONObject(rawBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.e(TAG, "No candidates in root agent response: ${rawBody.take(500)}")
                return null
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            if (parts == null) {
                Log.e(TAG, "No parts in root agent response: ${rawBody.take(500)}")
                return null
            }
            var functionCall: FunctionCallStep? = null
            var text: String? = null
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.optString("name", "")
                    val argsObj = fc.optJSONObject("args") ?: JSONObject()
                    val args = mutableMapOf<String, Any?>()
                    for (key in argsObj.keys()) args[key] = argsObj.opt(key)
                    if (name.isNotBlank()) functionCall = FunctionCallStep(name, args)
                } else if (part.has("text")) {
                    text = (text ?: "") + part.getString("text")
                }
            }
            RootAgentStep(functionCall, text?.trim()?.ifBlank { null })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse root agent response: ${e.message}, raw=${rawBody.take(500)}")
            null
        }
    }
}
