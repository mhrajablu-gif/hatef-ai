package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.ai.LocalPersianParser
import com.example.service.ai.ParsedTask
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEvent(val time: String, val event: String, val isError: Boolean = false)

data class BenchmarkItem(
    val title: String,
    val commandText: String,
    val description: String,
    val isLongWithPauses: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    // Engine States
    var activeEngineType by remember { mutableStateOf("DEFAULT") } // "DEFAULT", "ON_DEVICE", "SYSTEM_INTENT"
    var isListening by remember { mutableStateOf(false) }
    var currentPartial by remember { mutableStateOf("") }
    var finalResult by remember { mutableStateOf("") }
    var currentRms by remember { mutableFloatStateOf(0f) }
    val eventLogs = remember { mutableStateListOf<LogEvent>() }

    // Test text for local parser
    var testSentenceInput by remember { mutableStateOf("") }
    var parsedPreviewList by remember { mutableStateOf<List<ParsedTask>>(emptyList()) }

    var labSpeechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun addLog(msg: String, isError: Boolean = false) {
        val t = timeFormat.format(Date())
        eventLogs.add(0, LogEvent(t, msg, isError))
        if (eventLogs.size > 80) eventLogs.removeAt(eventLogs.lastIndex)
    }

    fun stopActiveSession() {
        try {
            labSpeechRecognizer?.stopListening()
            labSpeechRecognizer?.cancel()
            labSpeechRecognizer?.destroy()
        } catch (e: Exception) {
            addLog("Stop exception: ${e.message}", true)
        } finally {
            labSpeechRecognizer = null
            isListening = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopActiveSession()
        }
    }

    // Direct System Voice Intent launcher (The exact system dialog Gboard voice mic opens)
    val systemVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull() ?: ""
            addLog("System Voice Intent onResults: $text")
            finalResult = text
            currentPartial = ""
            testSentenceInput = text
            parsedPreviewList = LocalPersianParser.parse(text)
        } else {
            addLog("System Voice Intent Cancelled or No Match (resultCode: ${result.resultCode})", true)
        }
    }

    // Check On-Device Recognition capability (Android 12+ / API 31+)
    val isOnDeviceSupported = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }
    }

    fun startLabRecognition(useOnDevice: Boolean) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        stopActiveSession()
        currentPartial = ""
        finalResult = ""
        isListening = true
        activeEngineType = if (useOnDevice) "ON_DEVICE" else "DEFAULT"
        addLog("🚀 شروع جلسه تست: ${if (useOnDevice) "On-Device Recognizer" else "Default System Recognizer"}")

        try {
            val recognizer: SpeechRecognizer = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    addLog("❌ On-Device Recognition روی این دستگاه پشتیبانی نمی‌شود!", true)
                    isListening = false
                    return
                }
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            labSpeechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    addLog("🎙️ onReadyForSpeech (آماده شنیدن صدا)")
                }

                override fun onBeginningOfSpeech() {
                    addLog("🗣️ onBeginningOfSpeech (شروع تکلم کاربر)")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    currentRms = rmsdB
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    addLog("⏹️ onEndOfSpeech (پایان گفتار؛ موتور در حال تبدیل به متن است)")
                }

                override fun onError(errorCode: Int) {
                    val errorName = when (errorCode) {
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (خطای میکروفون دستگاه)"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (خطای داخلی کلاینت)"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (عدم مجوز)"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK (خطای شبکه اینترنت)"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT (پایان زمان شبکه)"
                        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (عبارت نامفهوم یا بدون صدا)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY (سرویس مشغول است)"
                        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER (خطای سرور گوگل)"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (عدم دریافت صدا در زمان مجاز)"
                        13 -> "ERROR_SERVER_DISCONNECTED (مدل آفلاین روی دستگاه نصب نیست)"
                        else -> "کد خطای ناشناخته ($errorCode)"
                    }
                    addLog("❌ onError: $errorName", true)
                    isListening = false

                    // If partial text was collected, preserve it
                    if (currentPartial.isNotBlank()) {
                        finalResult = currentPartial
                        testSentenceInput = currentPartial
                        parsedPreviewList = LocalPersianParser.parse(currentPartial)
                    }
                    stopActiveSession()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: currentPartial
                    addLog("✅ onResults: $text")
                    finalResult = text
                    currentPartial = ""
                    testSentenceInput = text
                    parsedPreviewList = LocalPersianParser.parse(text)
                    stopActiveSession()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        currentPartial = partial
                        addLog("📝 onPartialResults: $partial")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    addLog("🔔 onEvent ($eventType)")
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer.startListening(intent)

        } catch (e: Exception) {
            addLog("❌ Exception in startLabRecognition: ${e.message}", true)
            isListening = false
            stopActiveSession()
        }
    }

    // 5 Preset Benchmarks for testing
    val benchmarks = remember {
        listOf(
            BenchmarkItem(
                title = "فرمان ۱: یادآوری روز کاری با ساعت صبح",
                commandText = "فردا ساعت نه صبح جلسه کاری با مدیر عامل",
                description = "تک جمله‌ای با تاریخ نسبی («فردا») و ساعت صریح («نه صبح»)"
            ),
            BenchmarkItem(
                title = "فرمان ۲: تاریخ پس‌فردا و چند کار ترکیبی",
                commandText = "پس فردا ساعت سه بعدازظهر خرید نان سنگک و میوه",
                description = "آزمایش استخراج «پس‌فردا»، تبدیل ساعت ۳ عصر به ۱۵:۰۰ و عنوان خالص"
            ),
            BenchmarkItem(
                title = "فرمان ۳: زمان نسبی با دقیقه (تایمر فوری)",
                commandText = "نیم ساعت دیگه بهم یادآوری کن قرص مصرف کنم",
                description = "محاسبه آفلاین زمان بر اساس فاصله زمانی نسبی (+30 دقیقه)"
            ),
            BenchmarkItem(
                title = "فرمان ۴: یادآوری تکرار شونده روزانه",
                commandText = "هر روز ساعت هشت صبح یادآوری ورزش",
                description = "آزمایش استخراج دوره تکرار DAILY و تفکیک عنوان"
            ),
            BenchmarkItem(
                title = "فرمان ۵: جمله طولانی با مکث‌های طبیعی (تخلیه ذهن)",
                commandText = "سلام هاتف جان ... لطفاً فردا ساعت ده و نیم صبح ... یک یادآوری برای بررسی پیش‌نویس قرارداد شرکت تنظیم کن ... و همچنین پس فردا ساعت پنج عصر یادم بنداز با احمد تماس بگیرم.",
                description = "آزمایش گفتار پیوسته با مکث‌های طبیعی چند ثانیه‌ای و تفکیک دو کار جداگانه",
                isLongWithPauses = true
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "آزمایشگاه مستقل مقایسه ورودی صوتی",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Lab Header Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.BugReport,
                                contentDescription = null,
                                tint = TealPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "محیط مستقل تست و اعتبارسنجی ورودی صوتی (Voice Lab)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "این صفحه جهت مقایسه علمی ۳ روش دریافت صدا (سرویس پیش‌فرض، سرویس On-Device، و دیالوگ سیستمی Gboard) طراحی شده است تا پایداری و کیفیت زبان فارسی بدون نیاز به سرویس‌های پولی بررسی شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Engine Selection & Test Controls Card
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "۱. انتخاب و اجرای موتورهای تبدیل گفتار به متن:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Status of On-Device
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isOnDeviceSupported) EmeraldSuccess.copy(alpha = 0.12f) else AmberWarning.copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = if (isOnDeviceSupported) EmeraldSuccess else AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOnDeviceSupported) {
                                    "قابلیت On-Device Recognition در اندروید این دستگاه شناسایی شد (موجود)."
                                } else {
                                    "قابلیت On-Device Recognition در اندروید این دستگاه پشتیبانی نمی‌شود (اندروید ۱۲- یا عدم نصب پکیج آفلاین فارسی)."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Test Action Buttons
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Method 1: Default Android SpeechRecognizer
                            Button(
                                onClick = {
                                    if (isListening && activeEngineType == "DEFAULT") {
                                        stopActiveSession()
                                    } else {
                                        startLabRecognition(useOnDevice = false)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isListening && activeEngineType == "DEFAULT") CoralUrgent else TealPrimary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("test_default_recognizer_btn")
                            ) {
                                Icon(
                                    if (isListening && activeEngineType == "DEFAULT") Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isListening && activeEngineType == "DEFAULT")
                                        "توقف ضبط (سرویس پیش‌فرض)"
                                    else
                                        "آزمایش ۱: سرویس پیش‌فرض اندروید (Default SpeechRecognizer)"
                                )
                            }

                            // Method 2: On-Device SpeechRecognizer
                            Button(
                                onClick = {
                                    if (isListening && activeEngineType == "ON_DEVICE") {
                                        stopActiveSession()
                                    } else {
                                        startLabRecognition(useOnDevice = true)
                                    }
                                },
                                enabled = isOnDeviceSupported,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isListening && activeEngineType == "ON_DEVICE") CoralUrgent else MaterialTheme.colorScheme.secondary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("test_on_device_recognizer_btn")
                            ) {
                                Icon(
                                    if (isListening && activeEngineType == "ON_DEVICE") Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isListening && activeEngineType == "ON_DEVICE")
                                        "توقف ضبط (On-Device)"
                                    else
                                        "آزمایش ۲: موتور روی دستگاه (On-Device SpeechRecognizer)"
                                )
                            }

                            // Method 3: System Intent Dialog (Exact Gboard Voice prompt)
                            OutlinedButton(
                                onClick = {
                                    stopActiveSession()
                                    activeEngineType = "SYSTEM_INTENT"
                                    addLog("🚀 باز کردن دیالوگ صوتی سیستم عامل (RecognizerIntent)...")
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان یا یادآوری خود را بگویید...")
                                    }
                                    try {
                                        systemVoiceLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        addLog("خطا در باز کردن دیالوگ سیستم: ${e.message}", true)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("test_system_intent_btn")
                            ) {
                                Icon(Icons.Filled.SettingsVoice, contentDescription = null, modifier = Modifier.size(18.dp), tint = TealPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("آزمایش ۳: دیالوگ سیستمی صوت (موتور یکپارچه Gboard)")
                            }
                        }

                        // Live audio wave indicator
                        if (isListening) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "سطح سیگنال صوتی (RMS): ${String.format(Locale.ROOT, "%.1f dB", currentRms)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldSuccess
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val progress = ((currentRms + 2f) / 14f).coerceIn(0.05f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                color = EmeraldSuccess,
                                trackColor = EmeraldSuccess.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        }

                        // Live Partial or Final Text Display
                        if (currentPartial.isNotBlank() || finalResult.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = if (finalResult.isNotBlank()) "نتیجه نهایی تشخیص:" else "متن در حال تشخیص (Partial):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (finalResult.isNotBlank()) EmeraldSuccess else TealPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (finalResult.isNotBlank()) finalResult else currentPartial,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Benchmark Commands Card
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "۲. تست‌های استاندارد ۵ گانه (شامل مکث طبیعی و جمله بلند):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "هر عبارت را می‌توانید کپی کنید یا مستقیماً در پردازشگر محلی بیازمایید:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            benchmarks.forEachIndexed { index, bench ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (bench.isLongWithPauses)
                                        TealPrimary.copy(alpha = 0.1f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = if (bench.isLongWithPauses) androidx.compose.foundation.BorderStroke(1.dp, TealAccent) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = bench.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(bench.commandText))
                                                        addLog("متن فرمان کپی شد: ${bench.title}")
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Filled.ContentCopy, contentDescription = "کپی", modifier = Modifier.size(16.dp))
                                                }
                                                IconButton(
                                                    onClick = {
                                                        testSentenceInput = bench.commandText
                                                        parsedPreviewList = LocalPersianParser.parse(bench.commandText)
                                                        addLog("تحلیل محلی برای: ${bench.title}")
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Filled.PlayArrow, contentDescription = "آزمایش", tint = EmeraldSuccess, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "«${bench.commandText}»",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = bench.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Local Persian NLP Parser Test Box
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "۳. بررسی خروجی پردازشگر محلی ۱۰۰٪ آفلاین (بدون هزینه):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = testSentenceInput,
                            onValueChange = {
                                testSentenceInput = it
                                parsedPreviewList = LocalPersianParser.parse(it)
                            },
                            label = { Text("متن گفتاری یا آزمایشی") },
                            placeholder = { Text("مثال: فردا ساعت ۹ صبح جلسه با مدیر") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        if (parsedPreviewList.isNotEmpty()) {
                            Text(
                                text = "نتیجه استخراج معنایی (${parsedPreviewList.size} کار):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            parsedPreviewList.forEach { task ->
                                val analysis = task.analyzedResult
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        when (analysis?.validationStatus) {
                                            com.example.service.ai.ValidationStatus.VALID -> EmeraldSuccess
                                            com.example.service.ai.ValidationStatus.AMBIGUOUS -> AmberWarning
                                            com.example.service.ai.ValidationStatus.CONFLICT -> CoralUrgent
                                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🔹 عنوان: ${task.title}",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = when (analysis?.validationStatus) {
                                                    com.example.service.ai.ValidationStatus.VALID -> EmeraldSuccess.copy(alpha = 0.2f)
                                                    com.example.service.ai.ValidationStatus.AMBIGUOUS -> AmberWarning.copy(alpha = 0.2f)
                                                    com.example.service.ai.ValidationStatus.CONFLICT -> CoralUrgent.copy(alpha = 0.2f)
                                                    else -> Color.Gray.copy(alpha = 0.2f)
                                                }
                                            ) {
                                                Text(
                                                    text = when (analysis?.validationStatus) {
                                                        com.example.service.ai.ValidationStatus.VALID -> "✅ معتبر"
                                                        com.example.service.ai.ValidationStatus.AMBIGUOUS -> "⚠️ مبهم"
                                                        com.example.service.ai.ValidationStatus.CONFLICT -> "❌ متناقض"
                                                        else -> "نامشخص"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (analysis?.validationStatus) {
                                                        com.example.service.ai.ValidationStatus.VALID -> EmeraldSuccess
                                                        com.example.service.ai.ValidationStatus.AMBIGUOUS -> AmberWarning
                                                        com.example.service.ai.ValidationStatus.CONFLICT -> CoralUrgent
                                                        else -> Color.Gray
                                                    },
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "📅 تاریخ شمسی: ${task.jalaliDateText} | روز هفته: ${analysis?.calculatedWeekday ?: "نامشخص"}", style = MaterialTheme.typography.bodySmall)
                                        Text(text = "⏰ ساعت و دقیقه: ${task.timeText} (${if (analysis?.hour != null) "قطعی" else "نیازمند مشخص‌سازی صبح/عصر"})", style = MaterialTheme.typography.bodySmall, color = TealPrimary)
                                        Text(text = "🌐 منطقه زمانی: ${analysis?.timeZoneId ?: "پیش‌فرض"} | نوع زمان: ${analysis?.timeType ?: "مطلق"}", style = MaterialTheme.typography.labelSmall)
                                        Text(text = "💡 توضیح تحلیل: ${analysis?.reasoningExplanation ?: "تحلیل قاعده بومی"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

                                        if (analysis?.conflictMessage != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "⛔ ${analysis.conflictMessage}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CoralUrgent,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        if (task.isAmbiguous && task.clarificationQuestion != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "❓ پرسش از کاربر: ${task.clarificationQuestion}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AmberWarning,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (task.quickSuggestions.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    task.quickSuggestions.forEach { sug ->
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = TealPrimary.copy(alpha = 0.15f),
                                                            modifier = Modifier.clickable {
                                                                testSentenceInput = "$testSentenceInput $sug"
                                                                parsedPreviewList = LocalPersianParser.parse(testSentenceInput)
                                                            }
                                                        ) {
                                                            Text(
                                                                text = sug,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = TealPrimary,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Real-time Event Logger Console
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "۴. لاگ رویدادها و Callbackهای زنده موتور:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TealPrimary
                            )
                            IconButton(onClick = { eventLogs.clear() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Refresh, contentDescription = "پاکسازی", modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (eventLogs.isEmpty()) {
                                    item {
                                        Text(
                                            text = "هنوز رویدادی ثبت نشده است. یکی از آزمایش‌ها را آغاز کنید...",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                } else {
                                    items(eventLogs) { log ->
                                        Text(
                                            text = "[${log.time}] ${log.event}",
                                            color = if (log.isError) Color(0xFFFF6B6B) else Color(0xFF4ADE80),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
