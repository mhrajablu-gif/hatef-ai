package com.example.ui.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.service.alarm.AlarmSoundPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AppDatabase
import com.example.data.engine.PersianDateEngine
import com.example.service.alarm.AlarmScheduler
import com.example.service.speech.TtsSpeaker
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HatefAssistantTheme
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class FullScreenAlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ttsSpeaker: TtsSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure window to turn screen on and display over keyguard/lockscreen
        setupLockscreenFlags()

        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TITLE) ?: "یادآوری یادت"
        val desc = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_DESC) ?: ""
        val timeText = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TIME) ?: ""
        val soundUriStr = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_SOUND_URI)

        // Initialize sound and vibration
        startAlarmSoundAndVibration(soundUriStr)

        // Initialize TTS to announce reminder in Persian
        ttsSpeaker = TtsSpeaker(this).apply {
            speak("یادآوری یادت: $title")
        }

        setContent {
            HatefAssistantTheme(darkTheme = true) {
                FullScreenAlarmScreen(
                    title = title,
                    description = desc,
                    scheduledTime = timeText,
                    onComplete = {
                        stopAllAlerts()
                        cancelSystemNotification(reminderId)
                        completeReminderInDb(reminderId)
                        finish()
                    },
                    onSnooze = { minutes ->
                        stopAllAlerts()
                        cancelSystemNotification(reminderId)
                        snoozeReminderInDb(reminderId, minutes)
                        finish()
                    },
                    onDismiss = {
                        stopAllAlerts()
                        cancelSystemNotification(reminderId)
                        finish()
                    }
                )
            }
        }
    }

    private fun setupLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ensure complete immersive full-screen: hide system bars
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAlarmSoundAndVibration(soundUriStr: String?) {
        try {
            val soundUri = if (!soundUriStr.isNullOrBlank()) {
                Uri.parse(soundUriStr)
            } else {
                AlarmSoundPreferences.getResolvedSoundUri(this)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@FullScreenAlarmActivity, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 600, 300, 600, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAllAlerts() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}

        try {
            vibrator?.cancel()
            vibrator = null
        } catch (_: Exception) {}

        ttsSpeaker?.stop()
        ttsSpeaker?.destroy()
        ttsSpeaker = null
    }

    private fun cancelSystemNotification(reminderId: Long) {
        if (reminderId >= 0) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(reminderId.toInt())
        }
    }

    private fun completeReminderInDb(reminderId: Long) {
        if (reminderId <= 0) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Stop both the main and advance alarms when the user completes from full-screen UI.
                AlarmScheduler.cancelReminder(applicationContext, reminderId)
                val db = AppDatabase.getDatabase(applicationContext)
                db.reminderDao().setCompleted(reminderId, true, System.currentTimeMillis())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun snoozeReminderInDb(reminderId: Long, minutes: Int) {
        if (reminderId <= 0) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Remove stale main/advance alarms before writing the snoozed schedule.
                AlarmScheduler.cancelReminder(applicationContext, reminderId)
                val db = AppDatabase.getDatabase(applicationContext)
                val reminder = db.reminderDao().getReminderById(reminderId)
                if (reminder != null) {
                    val newTrigger = System.currentTimeMillis() + (minutes * 60 * 1000L)
                    val jDate = PersianDateEngine.timestampToJalali(newTrigger)
                    val timeStr = PersianDateEngine.formatPersianTime(jDate.hour, jDate.minute)
                    val dateStr = jDate.formatPersianNumeric()
                    val updated = reminder.copy(
                        triggerTimestamp = newTrigger,
                        jalaliDateText = dateStr,
                        timeText = timeStr,
                        isCompleted = false,
                        completedAt = null
                    )
                    db.reminderDao().update(updated)
                    AlarmScheduler.scheduleReminder(applicationContext, updated)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        stopAllAlerts()
        super.onDestroy()
    }
}

@Composable
fun FullScreenAlarmScreen(
    title: String,
    description: String,
    scheduledTime: String,
    onComplete: () -> Unit,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var currentTimeString by remember { mutableStateOf("") }
    var showSnoozeOptions by remember { mutableStateOf(false) }
    val currentJalali = PersianDateEngine.timestampToJalali(System.currentTimeMillis())
    val weekday = PersianDateEngine.getPersianWeekdayName(System.currentTimeMillis())

    LaunchedEffect(Unit) {
        while (true) {
            val cal = Calendar.getInstance()
            currentTimeString = PersianDateEngine.formatPersianTime(
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE)
            )
            delay(1000L)
        }
    }

    if (showSnoozeOptions) {
        AlertDialog(
            onDismissRequest = { showSnoozeOptions = false },
            title = { Text("مدت زمان تعویق") },
            text = { Text("یادآوری را چه مدت به تعویق بیندازم؟") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(5, 10, 15).forEach { minutes ->
                            TextButton(onClick = { showSnoozeOptions = false; onSnooze(minutes) }) {
                                Text(PersianDateEngine.toPersianDigits(minutes) + " دقیقه")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(30, 60).forEach { minutes ->
                            TextButton(onClick = { showSnoozeOptions = false; onSnooze(minutes) }) {
                                Text(if (minutes == 60) "۱ ساعت" else "۳۰ دقیقه")
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showSnoozeOptions = false }) { Text("انصراف") } }
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "alarm_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alarmPulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF031613),
                        Color(0xFF072922),
                        Color(0xFF001511)
                    )
                )
            )
            .padding(24.dp)
            .testTag("full_screen_alarm_view")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Clock & Date
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = "⏰ یادآوری فعال هاتف",
                    style = MaterialTheme.typography.titleMedium,
                    color = TealAccent,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = currentTimeString.ifEmpty { scheduledTime },
                    fontSize = 54.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$weekday، ${currentJalali.formatFullDate()}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }

            // Middle Section: Pulsing Icon & Task Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(130.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .background(TealPrimary.copy(alpha = 0.2f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(TealPrimary, Color(0xFF004D40))
                                ),
                                CircleShape
                            )
                            .border(2.dp, TealAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Alarm,
                            contentDescription = "آیکون آلارم",
                            tint = Color.White,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TealAccent.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        if (description.isNotBlank() && description != title) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }

                        if (scheduledTime.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "موعد مقرر: ساعت $scheduledTime",
                                style = MaterialTheme.typography.labelLarge,
                                color = TealAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Bottom Section: Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Done / Stop button
                Button(
                    onClick = onComplete,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("alarm_done_btn")
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "انجام شد و توقف آلارم",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Snooze 10 minutes button
                    Button(
                        onClick = { showSnoozeOptions = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3E50)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("alarm_snooze_btn")
                    ) {
                        Icon(Icons.Filled.Snooze, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تعویق",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Dismiss button
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = CoralUrgent.copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("alarm_dismiss_btn")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "بستن",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
