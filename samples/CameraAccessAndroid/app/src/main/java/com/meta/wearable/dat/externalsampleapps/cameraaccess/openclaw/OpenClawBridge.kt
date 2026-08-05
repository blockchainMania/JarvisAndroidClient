package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

// NOTE: This file replaces the original OpenClaw integration with a typed
// HTTP client for the **Jarvis Memory API**. Class name `OpenClawBridge` is
// preserved so the existing wiring in GeminiSessionViewModel / GeminiLiveService
// keeps compiling. Internally it is a Jarvis client. Rename to JarvisBridge
// in a follow-up if you want.

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * Investigated a reported "얼굴을 인식할 수 없다" identify_person failure: root agent tool
     * selection was verified correct via curl (it does call identify_person), but the backend
     * saw zero /people/identify requests during the actual test -- meaning the failure happened
     * client-side, before any network call, at VisualMemoryFrameStore.captureFreshVisual()'s
     * null check. Most likely cause: identify_person often runs moments after another tool
     * (save_person/capture_current_view) already triggered a real glasses capturePhoto() --
     * the glasses camera pipeline can still be busy from that, making the immediate next capture
     * attempt fail. One retry after a short delay gives that in-flight capture time to finish.
     */
    private suspend fun captureFreshVisualWithRetry(): VisualMemoryFrameStore.VisualFrame? {
        VisualMemoryFrameStore.captureFreshVisual()?.let { return it }
        Log.d(TAG, "First capture attempt returned no frame, retrying once after a short delay")
        delay(500L)
        return VisualMemoryFrameStore.captureFreshVisual()
    }

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
                    "save_person" -> {
                        val body = pick(args, "name", "aliases", "org", "role",
                                   "first_met_at", "last_met_at", "notes_summary")
                        val attachPhoto = args["attach_current_photo"] == true ||
                            args["attach_current_photo"]?.toString() == "true"
                        if (attachPhoto) {
                            // Must fail rather than silently save without a photo: a face-save
                            // request that "succeeds" with no image produces a person row with no
                            // face_embedding, which identify_person can never match later -- the
                            // exact bug this whole flow exists to prevent, and indistinguishable
                            // from a real success unless it's surfaced now.
                            val visualFrame = captureFreshVisualWithRetry()
                                ?: return@withContext ToolResult.Failure(
                                    "지금 카메라에서 사진을 가져오지 못했어요. 카메라 쪽을 봐주시고 다시 한번 저장을 요청해주시겠어요?"
                                )
                            body.put("image_base64", visualFrame.base64)
                            body.put("image_mime_type", "image/jpeg")
                        }
                        post("/people", body)
                    }
                    "identify_person" -> {
                        val visualFrame = captureFreshVisualWithRetry()
                            ?: return@withContext ToolResult.Failure(
                                "지금 카메라에서 사진을 가져오지 못했어요. 카메라 쪽을 봐주시고 다시 한번 말씀해주시겠어요?"
                            )
                        post(
                            "/people/identify",
                            JSONObject()
                                .put("image_base64", visualFrame.base64)
                                .put("image_mime_type", "image/jpeg")
                        )
                    }
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
                            "labels",
                            "entities",
                            "related_person_ids",
                            "source",
                        )
                        val capturedAtKst = nowKstIso()
                        // Callers that already captured a frame moments ago for a Flash read (see
                        // GeminiSessionViewModel.saveVisualReadDirectly) can pass it here directly
                        // instead of forcing another physical capturePhoto() -- on glasses that's a
                        // real shutter + ~2s delay, which is exactly the double-capture this avoids.
                        val visualFrame = args["__preCapturedVisualFrame"] as? VisualMemoryFrameStore.VisualFrame
                            ?: captureFreshVisualWithRetry()
                            ?: return@withContext ToolResult.Failure(
                                "지금 카메라에서 사진을 가져오지 못했어요. 카메라 쪽을 봐주시고 다시 한번 저장을 요청해주시겠어요?"
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
                                .put("visual_width", visualFrame.width)
                                .put("visual_height", visualFrame.height)
                                .put("visual_jpeg_bytes", visualFrame.jpegBytes)
                                .put("source_device", "meta_rayban_or_phone_camera")
                        )
                        post("/memory/life/save", body)
                    }
                    "search_memory" -> post(
                        "/memory/search",
                        pick(args, "query", "top_k", "time_from", "time_to", "person_id")
                    )
                    "universal_search" -> post(
                        "/memory/universal-search",
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
            } catch (e: IOException) {
                // Distinct from the generic catch below on purpose: this is specifically "the
                // request never reached/completed against the server" (timeout, DNS, connection
                // reset -- exactly what unstable cellular data produces), as opposed to a local
                // capture failure or a server-side rejection. Surfaced in Korean directly so the
                // root agent doesn't have to guess/paraphrase an OkHttp exception message, and
                // distinguishable in logs from every other failure mode when diagnosing reports
                // like "몰라도 사진은 찍혔는데 안 됨" without live device access.
                Log.e(TAG, "Tool $toolName network error: ${e.message}")
                ToolResult.Failure("네트워크 연결이 불안정해서 서버에 요청을 보내지 못했어요. 잠시 후 다시 시도해주시겠어요?")
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
            // The backend's validation errors (FastAPI's {"detail": "..."}) are already clean,
            // specific, correctly-worded Korean guidance (e.g. "얼굴이 잘 안 보여요. 정면으로
            // 다시 비춰주시겠어요?") -- pass that straight through instead of the raw
            // "HTTP 422: {...}" wrapper, so the root agent doesn't have to parse it out of a
            // technical string on its own to relay it faithfully to the user.
            val detail = try {
                JSONObject(responseBody).optString("detail").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            ToolResult.Failure(detail ?: "HTTP $statusCode: ${responseBody.take(200)}")
        }
    }

    // Keep these symbols referenced so unused-import lints stay quiet if
    // a downstream file imports JSONArray transitively. No runtime effect.
    @Suppress("unused")
    private fun debugArray(a: JSONArray) = a.length()
}
