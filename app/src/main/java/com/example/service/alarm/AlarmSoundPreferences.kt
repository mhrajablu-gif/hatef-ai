package com.example.service.alarm

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlarmSoundInfo(
    val uriString: String?,
    val title: String
)

class AlarmSoundPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hatef_alarm_sound_prefs", Context.MODE_PRIVATE)

    private val _currentSound = MutableStateFlow(loadCurrentSound())
    val currentSound: StateFlow<AlarmSoundInfo> = _currentSound.asStateFlow()

    private fun loadCurrentSound(): AlarmSoundInfo {
        val savedUri = prefs.getString(KEY_SOUND_URI, null)
        val defaultTitle = "صدای زنگ پیش‌فرض سیستم"
        val savedTitle = prefs.getString(KEY_SOUND_TITLE, defaultTitle) ?: defaultTitle
        return AlarmSoundInfo(savedUri, savedTitle)
    }

    fun setSound(uri: Uri?, title: String) {
        val uriStr = uri?.toString()
        prefs.edit()
            .putString(KEY_SOUND_URI, uriStr)
            .putString(KEY_SOUND_TITLE, title)
            .apply()
        _currentSound.value = AlarmSoundInfo(uriStr, title)
    }

    fun getResolvedSoundUri(): Uri {
        val saved = _currentSound.value.uriString
        return if (!saved.isNullOrBlank()) {
            Uri.parse(saved)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    companion object {
        private const val KEY_SOUND_URI = "key_alarm_sound_uri"
        private const val KEY_SOUND_TITLE = "key_alarm_sound_title"

        fun getSelectedSoundUri(context: Context): String? {
            val prefs = context.getSharedPreferences("hatef_alarm_sound_prefs", Context.MODE_PRIVATE)
            return prefs.getString(KEY_SOUND_URI, null)
        }

        fun getResolvedSoundUri(context: Context): Uri {
            val uriStr = getSelectedSoundUri(context)
            return if (!uriStr.isNullOrBlank()) {
                Uri.parse(uriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
        }
    }
}
