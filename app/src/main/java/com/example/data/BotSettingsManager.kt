package com.example.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.example.BuildConfig

class BotSettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("bot_settings_pref", Context.MODE_PRIVATE)

    var botToken = mutableStateOf(getStoredBotToken())
        private set

    var chatId = mutableStateOf(getStoredChatId())
        private set

    var isPollingActive = mutableStateOf(getStoredPollingActive())
        private set

    private fun getStoredBotToken(): String {
        val stored = prefs.getString("bot_token", "") ?: ""
        if (stored.isNotEmpty()) return stored
        // Fall back to BuildConfig / Environment Secret
        return try {
            BuildConfig.TELEGRAM_BOT_TOKEN.takeIf { !it.startsWith("YOUR_") } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun getStoredChatId(): String {
        val stored = prefs.getString("chat_id", "") ?: ""
        if (stored.isNotEmpty()) return stored
        // Fall back to BuildConfig / Environment Secret
        val fallback = try {
            BuildConfig.TELEGRAM_CHAT_ID.takeIf { !it.startsWith("YOUR_") } ?: "7686111113"
        } catch (e: Exception) {
            "7686111113"
        }
        return if (fallback.isEmpty()) "7686111113" else fallback
    }

    private fun getStoredPollingActive(): Boolean {
        return prefs.getBoolean("polling_active", false)
    }

    fun saveBotToken(value: String) {
        prefs.edit().putString("bot_token", value).apply()
        botToken.value = value
    }

    fun saveChatId(value: String) {
        prefs.edit().putString("chat_id", value).apply()
        chatId.value = value
    }

    fun savePollingActive(value: Boolean) {
        prefs.edit().putBoolean("polling_active", value).apply()
        isPollingActive.value = value
    }
}
