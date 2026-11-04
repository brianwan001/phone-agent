package tech.handsy.phoneagent.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Endpoint, key and model choice. No built-in credentials: the user provides their own API key. */
class SettingsStore(context: Context) {

    companion object {
        // Public, non-secret defaults (model names / endpoint). The API key has NO default.
        const val DEFAULT_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-4.5v"
        const val DEFAULT_PLANNER_MODEL = "glm-4.5-flash"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "agent_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 用户亲手填写的 key(未填时返回 null)。 */
    fun userApiKey(): String? = prefs.getString("apiKey", "")?.takeIf { it.isNotBlank() }

    /** 空值 = 清除用户 key。 */
    fun saveUserApiKey(value: String) {
        if (value.isBlank()) prefs.edit().remove("apiKey").apply()
        else prefs.edit().putString("apiKey", value).apply()
    }

    var endpoint: String
        get() = prefs.getString("endpoint", "").orEmpty().ifBlank { DEFAULT_ENDPOINT }
        set(value) = prefs.edit().putString("endpoint", value).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "").orEmpty()
        set(value) = prefs.edit().putString("apiKey", value).apply()

    var model: String
        get() = prefs.getString("model", "").orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString("model", value).apply()

    var plannerModel: String
        get() = prefs.getString("plannerModel", "").orEmpty().ifBlank { DEFAULT_PLANNER_MODEL }
        set(value) = prefs.edit().putString("plannerModel", value).apply()

    /** Skip the planner round trip entirely (faster; the actor then works plan-free). */
    /** 长时任务:步数上限(工作流场景可调到数百步)。 */
    var maxSteps: Int
        get() = prefs.getInt("maxSteps", 60)
        set(value) = prefs.edit().putInt("maxSteps", value.coerceIn(20, 999)).apply()

    /** 进程被杀重启后,自动提示/恢复中断的任务。 */
    var autoResume: Boolean
        get() = prefs.getBoolean("autoResume", true)
        set(value) = prefs.edit().putBoolean("autoResume", value).apply()

    var usePlanner: Boolean
        get() = prefs.getBoolean("usePlanner", false)
        set(value) = prefs.edit().putBoolean("usePlanner", value).apply()

    /** Ask thinking models (GLM-4.5V etc.) to skip their reasoning phase. */
    var disableThinking: Boolean
        get() = prefs.getBoolean("disableThinking", true)
        set(value) = prefs.edit().putBoolean("disableThinking", value).apply()

    val actorModelOrDefault: String get() = model.ifBlank { plannerModel }

    val plannerModelOrDefault: String get() = plannerModel.ifBlank { model }

    fun isConfigured(): Boolean = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
