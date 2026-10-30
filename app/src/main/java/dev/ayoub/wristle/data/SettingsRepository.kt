package dev.ayoub.wristle.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val apiKey: String,
    val model: String,
    val effort: String,
    val speakReplies: Boolean,
    val notificationPermissionAsked: Boolean,
)

data class ModelOption(
    val id: String,
    val label: String,
    val supportsFallbacks: Boolean,
)

class SettingsRepository(
    context: Context,
    private val cipher: SecretCipher,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun setApiKey(value: String) = update { putSecret(KEY_API_KEY, value) }

    fun cycleModel() =
        update {
            val ids = MODELS.map { it.id }
            putString(KEY_MODEL, ids[(ids.indexOf(state.value.model) + 1) % ids.size])
        }

    fun cycleEffort() = update { putString(KEY_EFFORT, EFFORTS[(EFFORTS.indexOf(state.value.effort) + 1) % EFFORTS.size]) }

    fun setSpeakReplies(value: Boolean) = update { putBoolean(KEY_SPEAK, value) }

    fun markNotificationPermissionAsked() = update { putBoolean(KEY_NOTIFICATION_ASKED, true) }

    private fun update(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = change)
        state.value = read()
    }

    private fun SharedPreferences.Editor.putSecret(
        key: String,
        value: String,
    ) {
        val trimmed = value.trim()
        val sealed = if (trimmed.isEmpty()) null else runCatching { cipher.encrypt(trimmed) }.getOrNull()
        putString(key, sealed.orEmpty())
    }

    private fun readSecret(key: String): String {
        val stored = prefs.getString(key, null).orEmpty()
        if (stored.isEmpty()) return ""
        if (cipher.isEncrypted(stored)) return cipher.decrypt(stored).orEmpty()
        prefs.edit { putSecret(key, stored) }
        return stored
    }

    private fun read() =
        AppSettings(
            apiKey = readSecret(KEY_API_KEY),
            model = prefs.getString(KEY_MODEL, null) ?: MODELS.first().id,
            effort = prefs.getString(KEY_EFFORT, null) ?: EFFORTS.first(),
            speakReplies = prefs.getBoolean(KEY_SPEAK, true),
            notificationPermissionAsked = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false),
        )

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_SPEAK = "speak"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"

        val MODELS =
            listOf(
                ModelOption("claude-opus-5-5", "Opus 5.5", supportsFallbacks = true),
                ModelOption("claude-sonnet-5-5", "Sonnet 5.5", supportsFallbacks = true),
                ModelOption("claude-haiku-5-5", "Haiku 5.5", supportsFallbacks = false),
            )
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        fun modelLabel(id: String): String = MODELS.firstOrNull { it.id == id }?.label ?: id
    }
}
