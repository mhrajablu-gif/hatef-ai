package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Manages long-term assistant memory and application preferences.
 * Fully controllable via conversational AI Tool Calling.
 */
class UserPreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yadet_user_preferences", Context.MODE_PRIVATE)

    private val _defaultAdvanceAlertMinutes = MutableStateFlow(loadDefaultAdvanceAlert())
    val defaultAdvanceAlertMinutes: StateFlow<Int> = _defaultAdvanceAlertMinutes.asStateFlow()

    private val _wakeUpTime = MutableStateFlow(prefs.getString(KEY_WAKE_UP_TIME, "۰۷:۰۰"))
    val wakeUpTime: StateFlow<String?> = _wakeUpTime.asStateFlow()

    private val _sleepTime = MutableStateFlow(prefs.getString(KEY_SLEEP_TIME, "۲۳:۰۰"))
    val sleepTime: StateFlow<String?> = _sleepTime.asStateFlow()

    private val _userMemories = MutableStateFlow(loadAllMemories())
    val userMemories: StateFlow<Map<String, String>> = _userMemories.asStateFlow()

    private fun loadDefaultAdvanceAlert(): Int {
        return prefs.getInt(KEY_DEFAULT_ADVANCE_ALERT, 0)
    }

    fun setDefaultAdvanceAlert(minutes: Int) {
        prefs.edit().putInt(KEY_DEFAULT_ADVANCE_ALERT, minutes).apply()
        _defaultAdvanceAlertMinutes.value = minutes
    }

    fun setWakeUpTime(time: String) {
        prefs.edit().putString(KEY_WAKE_UP_TIME, time).apply()
        _wakeUpTime.value = time
        saveMemory("ساعت_بیداری", time, "روتین روزانه")
    }

    fun setSleepTime(time: String) {
        prefs.edit().putString(KEY_SLEEP_TIME, time).apply()
        _sleepTime.value = time
        saveMemory("ساعت_خواب", time, "روتین خواب")
    }

    fun saveMemory(key: String, value: String, description: String = "اطلاعات کاربر") {
        val current = loadAllMemories().toMutableMap()
        current[key] = value
        saveMemoriesMap(current)
        _userMemories.value = current
    }

    fun deleteMemory(key: String): Boolean {
        val current = loadAllMemories().toMutableMap()
        val removed = current.remove(key) != null
        if (removed) {
            saveMemoriesMap(current)
            _userMemories.value = current
        }
        return removed
    }

    fun clearAllMemories() {
        prefs.edit().remove(KEY_MEMORIES_JSON).apply()
        _userMemories.value = emptyMap()
    }

    fun getMemory(key: String): String? {
        return _userMemories.value[key]
    }

    private fun loadAllMemories(): Map<String, String> {
        val jsonStr = prefs.getString(KEY_MEMORIES_JSON, null) ?: return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { k ->
                map[k] = json.getString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveMemoriesMap(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_MEMORIES_JSON, json.toString()).apply()
    }

    /**
     * Formats user memory and preferences for LLM system prompt / conversational reasoning context.
     */
    fun buildContextSummaryForAi(): String {
        val sb = StringBuilder()
        sb.append("اطلاعات و حافظه بلندمدت کاربر:\n")
        val alert = _defaultAdvanceAlertMinutes.value
        if (alert > 0) {
            sb.append("- پیش‌اعلان پیش‌فرض برای یادآورها: $alert دقیقه قبل\n")
        }
        _wakeUpTime.value?.let { sb.append("- ساعت بیداری معمول: $it\n") }
        _sleepTime.value?.let { sb.append("- ساعت خواب معمول: $it\n") }
        val mems = _userMemories.value
        if (mems.isNotEmpty()) {
            sb.append("- موارد ثبت‌شده در حافظه:\n")
            mems.forEach { (k, v) ->
                sb.append("  * $k: $v\n")
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val KEY_DEFAULT_ADVANCE_ALERT = "key_default_advance_alert"
        private const val KEY_WAKE_UP_TIME = "key_wake_up_time"
        private const val KEY_SLEEP_TIME = "key_sleep_time"
        private const val KEY_MEMORIES_JSON = "key_memories_json"
    }
}
