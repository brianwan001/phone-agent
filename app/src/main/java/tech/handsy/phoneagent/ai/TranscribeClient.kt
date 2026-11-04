package tech.handsy.phoneagent.ai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import tech.handsy.phoneagent.store.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

/** Cloud speech-to-text via the same OpenAI-compatible provider (glm-asr). */
object TranscribeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribe(context: Context, audio: File, model: String = "glm-asr"): String {
        val settings = SettingsStore(context)
        val base = settings.endpoint.trim().trimEnd('/')
        val url = if (base.endsWith("/v1")) base.removeSuffix("/v1") + "/audio/transcriptions"
        else if (base.endsWith("/v4")) base + "/audio/transcriptions"
        else base + "/v1/audio/transcriptions"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(120)
                throw Exception(msg)
            }
            return JSONObject(text).optString("text").trim()
        }
    }
}
