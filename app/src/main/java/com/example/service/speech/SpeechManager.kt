package com.example.service.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(
        val sessionId: String = "",
        val durationSeconds: Int = 0,
        val rmsDb: Float = 0f,
        val partialText: String = "",
        val chunkCount: Int = 0
    ) : VoiceState
    data object Processing : VoiceState
    data class Success(val recognizedText: String, val sessionId: String = "") : VoiceState
    data class Error(val message: String, val sessionId: String = "") : VoiceState
}

data class SessionAuditLog(
    val sessionId: String,
    val startTime: String,
    val endTime: String?,
    val durationMs: Long,
    val chunkCount: Int,
    val finalLength: Int,
    val stopReason: String
)

/**
 * Enterprise-grade Android SpeechRecognizer Manager.
 * Reuses a single SpeechRecognizer instance to avoid ERROR_RECOGNIZER_BUSY,
 * accumulates continuous multi-phrase speech across pauses,
 * gracefully releases audio hardware for Gboard keyboard microphone,
 * and maintains robust session lifecycle.
 */
class SpeechManager(private val context: Context) {
    private val TAG = "SpeechManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var isUserSessionActive = false
    private var currentSessionId = ""
    private var sessionStartTimeMs = 0L
    private var isCurrentlyListening = false
    private var utteranceCounter = 0

    private val accumulatedTextBuilder = StringBuilder()
    private var activeChunkPartial = ""

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<SessionAuditLog>>(emptyList())
    val auditLogs: StateFlow<List<SessionAuditLog>> = _auditLogs.asStateFlow()

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Starts listening. Reuses existing instance or creates once per session.
     */
    fun startListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening() }
            return
        }

        if (!isRecognitionAvailable()) {
            _voiceState.value = VoiceState.Error("سرویس تشخیص گفتار روی این دستگاه یافت نشد. لطفاً از دیالوگ صوتی سیستم استفاده فرمایید.")
            return
        }

        // Initialize fresh session
        currentSessionId = UUID.randomUUID().toString().take(8)
        sessionStartTimeMs = System.currentTimeMillis()
        isUserSessionActive = true
        utteranceCounter = 0
        accumulatedTextBuilder.clear()
        activeChunkPartial = ""

        val startLogTime = timeFormat.format(Date(sessionStartTimeMs))
        Log.i(TAG, "[Session $currentSessionId] START at $startLogTime")

        _voiceState.value = VoiceState.Recording(
            sessionId = currentSessionId,
            durationSeconds = 0,
            rmsDb = 0f,
            partialText = "",
            chunkCount = 0
        )

        ensureRecognizerInstance()
        startListeningIntent(currentSessionId)
    }

    private fun ensureRecognizerInstance() {
        if (speechRecognizer == null) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
            }
        }
    }

    private fun startListeningIntent(sessionId: String) {
        if (!isUserSessionActive || sessionId != currentSessionId) return
        val recognizer = speechRecognizer ?: return

        try {
            utteranceCounter++
            isCurrentlyListening = true
            activeChunkPartial = ""

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Generous silence lengths for natural Persian speech pauses
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 4500L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 3500L)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1500L)
            }

            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "[Session $sessionId] startListening error: ${e.message}")
            if (accumulatedTextBuilder.isNotBlank()) {
                finalizeSession(sessionId, "EXCEPTION_WITH_ACCUMULATED")
            } else {
                _voiceState.value = VoiceState.Error("خطا در راه‌اندازی میکروفون: ${e.message}", sessionId)
                cancelListening()
            }
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[Session $currentSessionId] onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[Session $currentSessionId] onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isUserSessionActive) return
                val fullDisplay = getFullLiveText()
                _voiceState.value = VoiceState.Recording(
                    sessionId = currentSessionId,
                    durationSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt(),
                    rmsDb = rmsdB,
                    partialText = fullDisplay,
                    chunkCount = utteranceCounter
                )
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "[Session $currentSessionId] onEndOfSpeech")
                isCurrentlyListening = false
                if (!isUserSessionActive) {
                    _voiceState.value = VoiceState.Processing
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isUserSessionActive) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotBlank()) {
                    activeChunkPartial = text
                    val fullDisplay = getFullLiveText()
                    _voiceState.value = VoiceState.Recording(
                        sessionId = currentSessionId,
                        durationSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt(),
                        rmsDb = 0f,
                        partialText = fullDisplay,
                        chunkCount = utteranceCounter
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                isCurrentlyListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: activeChunkPartial.trim()
                Log.d(TAG, "[Session $currentSessionId] onResults: \"$text\"")

                if (text.isNotBlank()) {
                    if (accumulatedTextBuilder.isNotEmpty()) {
                        accumulatedTextBuilder.append(" ")
                    }
                    accumulatedTextBuilder.append(text)
                }
                activeChunkPartial = ""

                if (isUserSessionActive) {
                    // Continuous recording across pauses: re-listen on the SAME instance!
                    val fullDisplay = accumulatedTextBuilder.toString().trim()
                    _voiceState.value = VoiceState.Recording(
                        sessionId = currentSessionId,
                        durationSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt(),
                        rmsDb = 0f,
                        partialText = fullDisplay,
                        chunkCount = utteranceCounter
                    )
                    mainHandler.postDelayed({
                        if (isUserSessionActive) {
                            startListeningIntent(currentSessionId)
                        }
                    }, 80)
                } else {
                    finalizeSession(currentSessionId, "USER_STOPPED_NORMAL")
                }
            }

            override fun onError(errorCode: Int) {
                isCurrentlyListening = false
                val errorName = when (errorCode) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "ERROR_CODE_$errorCode"
                }

                Log.w(TAG, "[Session $currentSessionId] onError: $errorName")

                // Preserve partial text
                if (activeChunkPartial.isNotBlank()) {
                    if (accumulatedTextBuilder.isNotEmpty()) {
                        accumulatedTextBuilder.append(" ")
                    }
                    accumulatedTextBuilder.append(activeChunkPartial.trim())
                    activeChunkPartial = ""
                }

                // If user is still actively recording and it's a silence timeout, simply resume listening!
                if (isUserSessionActive && (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    mainHandler.postDelayed({
                        if (isUserSessionActive) {
                            startListeningIntent(currentSessionId)
                        }
                    }, 100)
                    return
                }

                // If we already accumulated text, finalize with what we have
                if (accumulatedTextBuilder.isNotBlank()) {
                    finalizeSession(currentSessionId, "ERROR_WITH_ACCUMULATED_$errorName")
                    return
                }

                // Real error
                isUserSessionActive = false
                releaseRecognizer()
                val msg = when (errorCode) {
                    SpeechRecognizer.ERROR_AUDIO -> "خطای سخت‌افزار میکروفون دستگاه."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز دسترسی به میکروفون داده نشده است."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "خطای اتصال اینترنت جهت تبدیل گفتار."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "سرویس میکروفون مشغول بود. لطفاً مجدداً امتحان کنید."
                    else -> "صدایی شنیده نشد. لطفاً واضح‌تر صحبت کنید."
                }
                _voiceState.value = VoiceState.Error(msg, sessionId = currentSessionId)
                recordAudit(currentSessionId, "FATAL_$errorName", 0)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun getFullLiveText(): String {
        val acc = accumulatedTextBuilder.toString().trim()
        val part = activeChunkPartial.trim()
        return when {
            acc.isNotBlank() && part.isNotBlank() -> "$acc $part"
            acc.isNotBlank() -> acc
            else -> part
        }
    }

    /**
     * Called when user releases button or taps Stop.
     */
    fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopListening() }
            return
        }

        if (!isUserSessionActive) return
        Log.i(TAG, "[Session $currentSessionId] User explicitly stopped listening.")

        isUserSessionActive = false
        _voiceState.value = VoiceState.Processing

        try {
            if (isCurrentlyListening) {
                speechRecognizer?.stopListening()
            } else {
                finalizeSession(currentSessionId, "USER_RELEASED_BUTTON")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognizer: ${e.message}")
            finalizeSession(currentSessionId, "EXCEPTION_ON_STOP")
        }
    }

    private fun finalizeSession(sessionId: String, stopReason: String) {
        val finalText = getFullLiveText().trim()
        val durationMs = System.currentTimeMillis() - sessionStartTimeMs
        Log.i(TAG, "[Session $sessionId] FINALIZE ($stopReason) in ${durationMs}ms: \"$finalText\"")

        // Release recognizer audio resources immediately so Gboard has full microphone access!
        releaseRecognizer()
        recordAudit(sessionId, stopReason, finalText.length)

        if (finalText.isNotBlank()) {
            _voiceState.value = VoiceState.Success(finalText, sessionId = sessionId)
        } else {
            _voiceState.value = VoiceState.Error("صدایی شنیده نشد. لطفاً روی دکمه میکروفون بزنید و واضح صحبت کنید.", sessionId)
        }
    }

    private fun recordAudit(sessionId: String, stopReason: String, finalLength: Int) {
        val now = System.currentTimeMillis()
        val audit = SessionAuditLog(
            sessionId = sessionId,
            startTime = timeFormat.format(Date(sessionStartTimeMs)),
            endTime = timeFormat.format(Date(now)),
            durationMs = now - sessionStartTimeMs,
            chunkCount = utteranceCounter,
            finalLength = finalLength,
            stopReason = stopReason
        )
        val list = _auditLogs.value.toMutableList()
        list.add(0, audit)
        if (list.size > 50) list.removeAt(list.lastIndex)
        _auditLogs.value = list
    }

    fun cancelListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelListening() }
            return
        }

        isUserSessionActive = false
        releaseRecognizer()
        accumulatedTextBuilder.clear()
        activeChunkPartial = ""
        _voiceState.value = VoiceState.Idle
    }

    fun resetState() {
        cancelListening()
    }

    fun deliverManualText(text: String) {
        _voiceState.value = VoiceState.Success(text.trim(), UUID.randomUUID().toString().take(8))
    }

    private fun releaseRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "releaseRecognizer error", e)
        } finally {
            speechRecognizer = null
            isCurrentlyListening = false
        }
    }

    fun destroy() {
        cancelListening()
    }
}
