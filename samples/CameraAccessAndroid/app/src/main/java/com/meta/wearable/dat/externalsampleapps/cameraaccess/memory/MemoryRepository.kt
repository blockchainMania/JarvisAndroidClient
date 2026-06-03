package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MemoryRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json".toMediaType()

    fun recent(limit: Int = 30): List<MemoryItem> {
        val body = JSONObject()
            .put("limit", limit)
            .put("memory_type", "life_scene")
        return postMemories("/memory/recent", body)
    }

    fun search(query: String, topK: Int = 20): List<MemoryItem> {
        val body = JSONObject()
            .put("query", query)
            .put("top_k", topK)
        val response = postRaw("/memory/search", body)
        val items = mutableListOf<MemoryItem>()
        for (i in 0 until response.length()) {
            val wrapper = response.getJSONObject(i)
            items.add(parseMemory(wrapper.getJSONObject("memory")))
        }
        return items
    }

    private fun postMemories(path: String, body: JSONObject): List<MemoryItem> {
        val response = postRaw(path, body)
        val items = mutableListOf<MemoryItem>()
        for (i in 0 until response.length()) {
            items.add(parseMemory(response.getJSONObject(i)))
        }
        return items
    }

    private fun postRaw(path: String, body: JSONObject): org.json.JSONArray {
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${responseBody.take(160)}")
            }
            return org.json.JSONArray(responseBody)
        }
    }

    private fun parseMemory(json: JSONObject): MemoryItem {
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        return MemoryItem(
            id = json.optString("id"),
            capturedAt = json.optString("captured_at"),
            text = json.optString("text"),
            userNote = metadata.optString("user_note").ifBlank { null },
            aiInterpretation = metadata.optString("ai_interpretation").ifBlank { null },
            peopleText = metadata.optString("people_text").ifBlank { null },
            imageFilename = metadata.optString("image_filename").ifBlank { null },
        )
    }
}
