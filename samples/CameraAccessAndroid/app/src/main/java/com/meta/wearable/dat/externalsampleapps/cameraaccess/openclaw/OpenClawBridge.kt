package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

// NOTE: This file replaces the original OpenClaw integration with a typed
// HTTP client for the **Jarvis Memory API**. Class name `OpenClawBridge` is
// preserved so the existing wiring in GeminiSessionViewModel / GeminiLiveService
// keeps compiling. Internally it is a Jarvis client. Rename to JarvisBridge
// in a follow-up if you want.

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenClawBridge {
    companion object {
        private const val TAG = "JarvisBridge"
    }

    private val _lastToolCallStatus = MutableStateFlow<ToolCallStatus>(ToolCallStatus.Idle)
    val lastToolCallStatus: StateFlow<ToolCallStatus> = _lastToolCallStatus.asStateFlow()

    private val _connectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.NotConfigured)
    val connectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()

    fun setToolCallStatus(status: ToolCallStatus) {
        _lastToolCallStatus.value = status
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private fun baseUrl(): String = GeminiConfig.jarvisApiBase.trimEnd('/')

    private fun nowKstIso(): String =
        ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    // ─── Connection check (used by UI badge) ─────────────────────
    suspend fun checkConnection() = withContext(Dispatchers.IO) {
        if (!GeminiConfig.isJarvisConfigured) {
            _connectionState.value = OpenClawConnectionState.NotConfigured
            return@withContext
        }
        _connectionState.value = OpenClawConnectionState.Checking
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/health")
                .get()
                .build()
            val response = pingClient.newCall(request).execute()
            val code = response.code
            response.close()
            if (code == 200) {
                _connectionState.value = OpenClawConnectionState.Connected
                Log.d(TAG, "Jarvis API reachable")
            } else {
                _connectionState.value = OpenClawConnectionState.Unreachable("HTTP $code")
            }
        } catch (e: Exception) {
            _connectionState.value = OpenClawConnectionState.Unreachable(e.message ?: "Unknown")
            Log.d(TAG, "Jarvis API unreachable: ${e.message}")
        }
    }

    fun resetSession() {
        // Stateless: no conversation history kept on this side.
    }

    // ─── Typed dispatch — called by ToolCallRouter ───────────────
    suspend fun dispatch(toolName: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.IO) {
            _lastToolCallStatus.value = ToolCallStatus.Executing(toolName)
            val result: ToolResult = try {
                when (toolName) {
                    "save_person" -> post(
                        "/people",
                        pick(args, "name", "aliases", "org", "role",
                                   "first_met_at", "last_met_at", "notes_summary")
                    )
                    "search_people" -> post(
                        "/people/search",
                        pick(args, "query", "top_k")
                    )
                    "save_meeting" -> post(
                        "/meetings",
                        pick(args, "title", "person_ids", "started_at", "ended_at",
                                   "location", "summary", "raw_transcript")
                    )
                    "search_meetings" -> post(
                        "/meetings/search",
                        pick(args, "query", "top_k", "time_from", "time_to", "person_id")
                    )
                    "save_memory" -> post(
                        "/memory/save",
                        pick(args, "text", "captured_at", "related_person_ids",
                                   "related_meeting_id", "source")
                    )
                    "save_life_memory" -> {
                        val body = pick(
                            args,
                            "user_note",
                            "ai_interpretation",
                            "people_text",
                            "related_person_ids",
                            "source",
                        )
                        val capturedAtKst = nowKstIso()
                        val visualFrame = VisualMemoryFrameStore.captureFreshVisual()
                            ?: return@withContext ToolResult.Failure(
                                "No fresh camera image is available. Ask the user to hold still for a moment and try saving again."
                            )
                        body.put("captured_at", capturedAtKst)
                        body.put("image_base64", visualFrame.base64)
                        body.put("image_mime_type", "image/jpeg")
                        body.put(
                            "metadata",
                            JSONObject()
                                .put("captured_at_kst", capturedAtKst)
                                .put("frame_captured_at_ms", visualFrame.capturedAtMs)
                                .put("frame_age_ms_at_save", visualFrame.ageMs)
                                .put("visual_source", visualFrame.source)
                                .put("source_device", "meta_rayban_or_phone_camera")
                        )
                        post("/memory/life/save", body)
                    }
                    "search_memory" -> post(
                        "/memory/search",
                        pick(args, "query", "top_k", "time_from", "time_to", "person_id")
                    )
                    "save_need" -> post(
                        "/needs",
                        pick(args, "person_id", "meeting_id", "text", "category", "confidence")
                    )
                    "get_proposal_context" -> {
                        val pid = args["person_id"]?.toString()
                            ?: return@withContext ToolResult.Failure("person_id required")
                        get("/people/$pid/context")
                    }
                    else -> ToolResult.Failure("Unknown tool: $toolName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tool $toolName error: ${e.message}")
                ToolResult.Failure(e.message ?: "Unknown error")
            }
            _lastToolCallStatus.value = when (result) {
                is ToolResult.Success -> ToolCallStatus.Completed(toolName)
                is ToolResult.Failure -> ToolCallStatus.Failed(toolName, result.error)
            }
            result
        }

    // ─── Backward-compatible single-task entrypoint ──────────────
    // Legacy callers (e.g. an `execute(task=...)` Gemini tool, if you ever
    // re-add one) will be routed here. Default behavior: treat the task as
    // a memory search query.
    suspend fun delegateTask(task: String, toolName: String = "execute"): ToolResult {
        return dispatch("search_memory", mapOf("query" to task, "top_k" to 5))
    }

    // ─── HTTP helpers ────────────────────────────────────────────
    private fun pick(args: Map<String, Any?>, vararg allowed: String): JSONObject {
        val allowSet = allowed.toSet()
        val o = JSONObject()
        for ((k, v) in args) {
            if (k !in allowSet) continue
            if (v == null || v == JSONObject.NULL) continue
            o.put(k, v)  // org.json handles String/Int/Double/Boolean/JSONObject/JSONArray
        }
        return o
    }

    private fun post(path: String, body: JSONObject): ToolResult {
        val request = Request.Builder()
            .url("${baseUrl()}$path")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        return execute(request, "POST $path", body)
    }

    private fun get(path: String): ToolResult {
        val request = Request.Builder()
            .url("${baseUrl()}$path")
            .get()
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .build()
        return execute(request, "GET $path", null)
    }

    private fun execute(request: Request, label: String, requestBody: JSONObject?): ToolResult {
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        val statusCode = response.code
        response.close()
        if (requestBody != null) Log.d(TAG, "$label body=${requestBody.toString().take(200)}")
        Log.d(TAG, "$label -> HTTP $statusCode resp=${responseBody.take(200)}")
        return if (statusCode in 200..299) {
            ToolResult.Success(responseBody)
        } else {
            ToolResult.Failure("HTTP $statusCode: ${responseBody.take(200)}")
        }
    }

    // Keep these symbols referenced so unused-import lints stay quiet if
    // a downstream file imports JSONArray transitively. No runtime effect.
    @Suppress("unused")
    private fun debugArray(a: JSONArray) = a.length()
}
