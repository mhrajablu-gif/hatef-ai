package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.alarm.AlarmScheduler
import com.example.service.alarm.AlarmSoundPreferences
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()

    val soundPrefs = remember { AlarmSoundPreferences(context) }
    val currentSound by soundPrefs.currentSound.collectAsState()

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlayingPreview by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        }
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val ringtoneTitle = if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone?.getTitle(context) ?: "صدای زنگ انتخابی"
            } else {
                "صدای پیش‌فرض سیستم"
            }
            soundPrefs.setSound(uri, ringtoneTitle)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "دستیار هوشمند یادت (Yadet)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "نسخه ۱.۰۴ | موتور تقویم، گفتار پیوسته و آلارم تمام صفحه",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section: Theme & Appearance (تم تاریک و روشن)
        item {
            SectionCard(
                title = "قالب و پوسته ظاهری (تم)",
                icon = Icons.Filled.Palette
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "حالت نمایش مورد علاقه خود را انتخاب کنید:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeOptionCard(
                            title = "☀️ روشن",
                            isSelected = themeMode == com.example.ui.theme.ThemeMode.LIGHT,
                            onClick = { viewModel.setThemeMode(com.example.ui.theme.ThemeMode.LIGHT) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemeOptionCard(
                            title = "🌙 تاریک",
                            isSelected = themeMode == com.example.ui.theme.ThemeMode.DARK,
                            onClick = { viewModel.setThemeMode(com.example.ui.theme.ThemeMode.DARK) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemeOptionCard(
                            title = "⚙️ سیستم",
                            isSelected = themeMode == com.example.ui.theme.ThemeMode.SYSTEM,
                            onClick = { viewModel.setThemeMode(com.example.ui.theme.ThemeMode.SYSTEM) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Section: Sound & Alerts
        item {
            SectionCard(
                title = "هشدارها و صدای آلارم",
                icon = Icons.Filled.Notifications
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Current sound row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "صدای زنگ هشدار",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentSound.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }

                        // Play/Stop preview button
                        Button(
                            onClick = {
                                if (isPlayingPreview) {
                                    previewPlayer?.stop()
                                    previewPlayer?.release()
                                    previewPlayer = null
                                    isPlayingPreview = false
                                } else {
                                    try {
                                        previewPlayer?.release()
                                        val uri = soundPrefs.getResolvedSoundUri()
                                        previewPlayer = MediaPlayer().apply {
                                            setDataSource(context, uri)
                                            prepare()
                                            start()
                                            setOnCompletionListener {
                                                isPlayingPreview = false
                                                release()
                                                previewPlayer = null
                                            }
                                        }
                                        isPlayingPreview = true
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        isPlayingPreview = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayingPreview) CoralUrgent else TealPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                if (isPlayingPreview) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPlayingPreview) "توقف" else "پیش‌شنیدن", fontSize = 12.sp)
                        }
                    }

                    // Choose ringtone button
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION
                                )
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای هشدار یادت")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                val currentUri = soundPrefs.getResolvedSoundUri()
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                            }
                            ringtonePickerLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("انتخاب و تغییر صدای زنگ از حافظه گوشی")
                    }

                    // Test Full-Screen Alarm button
                    Button(
                        onClick = {
                            val testIntent = Intent(context, com.example.ui.alarm.FullScreenAlarmActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(AlarmScheduler.EXTRA_REMINDER_ID, 999999L)
                                putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, "آزمایش آلارم تمام صفحه یادت")
                                putExtra(AlarmScheduler.EXTRA_REMINDER_DESC, "این صفحه حتی در صورت قفل بودن صفحه گوشی نمایش داده شده و با صدای انتخابی شما پخش می‌شود.")
                                putExtra(AlarmScheduler.EXTRA_REMINDER_TIME, "آزمایش زنده")
                                putExtra(AlarmScheduler.EXTRA_REMINDER_SOUND_URI, soundPrefs.currentSound.value.uriString)
                            }
                            context.startActivity(testIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("⚡️ آزمایش زنده آلارم تمام صفحه (صفحه قفل)")
                    }
                }
            }
        }

        // Section: Voice Lab & Speech Engines Comparison (آزمایشگاه مستقل ورودی صوتی)
        item {
            SectionCard(
                title = "آزمایشگاه صوتی و مقایسه موتورها",
                icon = Icons.Filled.SettingsVoice
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "نمونه آزمایشی مستقل برای مقایسه سرویس پیش‌فرض اندروید، تشخیص روی دستگاه (On-Device) و دیالوگ صوتی Gboard روی ۵ فرمان فارسی استاندارد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    Button(
                        onClick = { viewModel.openVoiceLab() },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔬 ورود به آزمایشگاه مستقل ورودی صوتی", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section: Privacy & Local Offline Architecture
        item {
            SectionCard(
                title = "امنیت و حریم خصوصی (معماری آفلاین)",
                icon = Icons.Filled.Security
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivacyBullet(
                        title = "پایگاه داده ۱۰۰٪ محلی (Room)",
                        desc = "کلیه یادآوری‌ها و وظایف روی حافظه داخلی گوشی شما ذخیره شده و هیچ داده‌ای در سرور خارجی ذخیره نمی‌شود."
                    )
                    PrivacyBullet(
                        title = "عملکرد کامل بدون اینترنت",
                        desc = "تمامی یادآوری‌های ذخیره‌شده از طریق AlarmManager حتی بدون اینترنت در موعد مقرر زنگ می‌زنند."
                    )
                    PrivacyBullet(
                        title = "کنترل ضبط صوت (Push-to-Talk)",
                        desc = "صدا صرفاً زمانی که دکمه میکروفون فشرده نگه داشته شده ضبط می‌شود و با رها کردن فوراً متوقف می‌گردد."
                    )
                }
            }
        }

        // Section: Technical Specifications
        item {
            SectionCard(
                title = "اطلاعات فنی سیستم",
                icon = Icons.Filled.Info
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TechRow(label = "زبان و رابط کاربری:", value = "Kotlin + Jetpack Compose")
                    TechRow(label = "موتور تقویم:", value = "تقویم جلالی (شمسی) نجومی بومی")
                    TechRow(label = "پایگاه داده:", value = "Android Room SQLite")
                    TechRow(label = "هوش مصنوعی معنایی:", value = "Gemini Flash + موتور محلی NLP فارسی")
                    TechRow(label = "تایپوگرافی:", value = "فونت محلی Vazirmatn")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PrivacyBullet(title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = EmeraldSuccess,
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = TealPrimary)
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) TealPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, TealAccent) else null,
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
