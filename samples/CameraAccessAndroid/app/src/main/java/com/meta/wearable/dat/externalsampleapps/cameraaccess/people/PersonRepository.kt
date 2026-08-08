package com.meta.wearable.dat.externalsampleapps.cameraaccess.people

import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class PersonRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json".toMediaType()
    private val kstZone = ZoneId.of("Asia/Seoul")
    private val displayFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun list(limit: Int = 100): List<PersonItem> {
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}/people?limit=$limit")
            .get()
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .build()
        val array = execute(request)
        val items = mutableListOf<PersonItem>()
        for (i in 0 until array.length()) items.add(parsePerson(array.getJSONObject(i)))
        return items
    }

    fun search(query: String, topK: Int = 30): List<PersonItem> {
        val body = JSONObject().put("query", query).put("top_k", topK)
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}/people/search")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        val array = execute(request)
        val items = mutableListOf<PersonItem>()
        for (i in 0 until array.length()) items.add(parsePerson(array.getJSONObject(i).getJSONObject("person")))
        return items
    }

    // Field-by-field edit -- every field here is always sent explicitly (blank text becomes
    // JSONObject.NULL, which the backend's PATCH treats as "clear this field") since the edit
    // dialog always shows and lets the user touch every field, unlike the voice update_person
    // tool which only sends whatever the user actually mentioned in that turn.
    fun update(person: PersonItem): PersonItem {
        val body = JSONObject()
            .put("name", person.name)
            .put("aliases", JSONArray(person.aliases))
            .put("org", person.org ?: JSONObject.NULL)
            .put("role", person.role ?: JSONObject.NULL)
            .put("phone", person.phone ?: JSONObject.NULL)
            .put("email", person.email ?: JSONObject.NULL)
            .put("address", person.address ?: JSONObject.NULL)
            .put("notes_summary", person.notesSummary ?: JSONObject.NULL)
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}/people/${person.id}")
            .patch(body.toString().toRequestBody(jsonMediaType))
            .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${responseBody.take(160)}")
            }
            return parsePerson(JSONObject(responseBody))
        }
    }

    private fun execute(request: Request): JSONArray {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${responseBody.take(160)}")
            }
            return JSONArray(responseBody)
        }
    }

    private fun parsePerson(json: JSONObject): PersonItem {
        val aliases = json.optJSONArray("aliases")
        return PersonItem(
            id = json.optString("id"),
            name = json.optString("name"),
            aliases = (0 until (aliases?.length() ?: 0)).map { aliases!!.getString(it) },
            org = json.optString("org").ifBlank { null },
            role = json.optString("role").ifBlank { null },
            phone = json.optString("phone").ifBlank { null },
            email = json.optString("email").ifBlank { null },
            address = json.optString("address").ifBlank { null },
            notesSummary = json.optString("notes_summary").ifBlank { null },
            updatedAtDisplay = formatKst(json.optString("updated_at")),
        )
    }

    private fun formatKst(raw: String): String = try {
        ZonedDateTime.parse(raw).withZoneSameInstant(kstZone).format(displayFormatter)
    } catch (_: Exception) {
        try {
            Instant.parse(raw).atZone(kstZone).format(displayFormatter)
        } catch (_: Exception) {
            raw.take(16)
        }
    }
}
