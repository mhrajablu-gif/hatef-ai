package com.example.service.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsSpeaker(context: Context) {
    private val TAG = "TtsSpeaker"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                try {
                    val persianLocale = Locale("fa", "IR")
                    val result = tts?.setLanguage(persianLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Persian TTS language not fully supported, using default locale")
                        tts?.language = Locale.getDefault()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set TTS language", e)
                }
            } else {
                Log.w(TAG, "TTS init failed with status: $status")
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized || text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HatefTts_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error", e)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}
