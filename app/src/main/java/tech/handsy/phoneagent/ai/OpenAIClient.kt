package tech.handsy.phoneagent.ai

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIClient(private val endpoint: String, private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        // Hard ceiling per call: a byte-trickling stalled connection must not hang a step forever.
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        var base = endpoint.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        if (!Regex("/v\\d+$").containsMatchIn(base)) base += "/v1"
        return base
    }

    fun listModels(): List<String> {
        val request = Request.Builder()
            .url(baseUrl() + "/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(300)}")
            val data = JSONObject(text).getJSONArray("data")
            val models = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotBlank()) models.add(id)
            }
            return models.sorted()
        }
    }

    /**
     * One chat completion. [screenshot] is sent as a downscaled JPEG data URL.
     * [disableThinking] adds the Zhipu-style `thinking: disabled` flag (GLM-4.5V etc.),
     * which most OpenAI-compatible servers simply ignore.
     * Returns the assistant message text.
     */
    fun chat(
        model: String,
        system: String,
        userText: String,
        screenshot: Bitmap?,
        disableThinking: Boolean = false,
        zoomCrop: Bitmap? = null,
        historyShots: List<Bitmap> = emptyList(),
        allowThinking: Boolean = false,
    ): String {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", userText),
        )
        historyShots.forEachIndexed { i, bmp ->
            content.put(JSONObject().put("type", "text").put("text", "PREVIOUS SCREEN (${i + 1} step(s) ago):"))
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${encode(bmp)}")),
            )
        }
        if (screenshot != null) {
            content.put(
                JSONObject().put("type", "text").put("text", "CURRENT SCREEN:")
            )
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${encode(screenshot)}")),
            )
        }
        if (zoomCrop != null) {
            content.put(
                JSONObject().put("type", "text").put("text", "ZOOMED VIEW around your previous missed target (3x):")
            )
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${encode(zoomCrop)}")),
            )
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 2048)
            .put("temperature", 0.2)
        if (disableThinking && !allowThinking) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        val request = Request.Builder()
            .url(baseUrl() + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(300)}")
            val json = JSONObject(text)
            val message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            // Thinking models sometimes leave only reasoning_content when the answer is short.
            return message.optString("content").ifBlank { message.optString("reasoning_content") }
        }
    }

    companion object {
        private const val MAX_SIDE = 1100
        private const val JPEG_QUALITY = 65

        /** Downscales for the model and encodes as base64 JPEG. */
        fun encode(bitmap: Bitmap, maxSide: Int = MAX_SIDE): String {
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val shot = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { out ->
                shot.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
