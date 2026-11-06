package com.example.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel
import java.util.Calendar

@Composable
fun CalendarScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val allReminders by viewModel.allReminders.collectAsState()
    val dayReminders by viewModel.calendarDayReminders.collectAsState()

    val currentNow = System.currentTimeMillis()
    val todayJalali = PersianDateEngine.timestampToJalali(currentNow)

    var viewingYear by remember { mutableIntStateOf(uiState.selectedCalendarDate.year) }
    var viewingMonth by remember { mutableIntStateOf(uiState.selectedCalendarDate.month) }

    val daysInMonth = PersianDateEngine.getDaysInMonth(viewingYear, viewingMonth)
    val firstDayTimestamp = PersianDateEngine.jalaliToTimestamp(viewingYear, viewingMonth, 1, 12, 0)
    val cal = Calendar.getInstance().apply { timeInMillis = firstDayTimestamp }
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    // Map Java Calendar day of week to Persian index: Sat=0, Sun=1, Mon=2, Tue=3, Wed=4, Thu=5, Fri=6
    val firstDayOffset = when (dayOfWeek) {
        Calendar.SATURDAY -> 0
        Calendar.SUNDAY -> 1
        Calendar.MONDAY -> 2
        Calendar.TUESDAY -> 3
        Calendar.WEDNESDAY -> 4
        Calendar.THURSDAY -> 5
        Calendar.FRIDAY -> 6
        else -> 0
    }

    val selectedHoliday = PersianDateEngine.getHoliday(uiState.selectedCalendarDate.month, uiState.selectedCalendarDate.day)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Month Selector Header
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (viewingMonth > 1) {
                                viewingMonth--
                            } else {
                                viewingMonth = 12
                                viewingYear--
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ماه قبل")
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${PersianDateEngine.MONTH_NAMES[viewingMonth - 1]} ${PersianDateEngine.toPersianDigits(viewingYear)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (viewingYear == todayJalali.year && viewingMonth == todayJalali.month) {
                            Text(
                                text = "ماه جاری",
                                style = MaterialTheme.typography.labelSmall,
                                color = TealAccent
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (viewingMonth < 12) {
                                viewingMonth++
                            } else {
                                viewingMonth = 1
                                viewingYear++
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "ماه بعد")
                    }
                }
            }
        }

        // Calendar Grid Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Weekday headers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEachIndexed { i, label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (i == 6) CoralUrgent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Grid of days
                    val totalCells = firstDayOffset + daysInMonth
                    val rows = (totalCells + 6) / 7

                    for (row in 0 until rows) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            for (col in 0..6) {
                                val cellIndex = row * 7 + col
                                val dayNum = cellIndex - firstDayOffset + 1

                                if (dayNum in 1..daysInMonth) {
                                    val isToday = viewingYear == todayJalali.year &&
                                            viewingMonth == todayJalali.month &&
                                            dayNum == todayJalali.day

                                    val isSelected = viewingYear == uiState.selectedCalendarDate.year &&
                                            viewingMonth == uiState.selectedCalendarDate.month &&
                                            dayNum == uiState.selectedCalendarDate.day

                                    val holiday = PersianDateEngine.getHoliday(viewingMonth, dayNum)
                                    val isFriday = col == 6

                                    // Check if there are tasks on this day
                                    val dateStr = String.format("%04d/%02d/%02d", viewingYear, viewingMonth, dayNum)
                                    val hasTasks = allReminders.any {
                                        PersianDateEngine.toEnglishDigits(it.jalaliDateText) == dateStr
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isSelected -> TealPrimary
                                                    isToday -> MaterialTheme.colorScheme.primaryContainer
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                viewModel.selectCalendarDate(
                                                    PersianDateEngine.JalaliDate(viewingYear, viewingMonth, dayNum)
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = PersianDateEngine.toPersianDigits(dayNum),
                                                fontSize = 13.sp,
                                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> Color.White
                                                    holiday != null || isFriday -> CoralUrgent
                                                    isToday -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )

                                            if (hasTasks) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSelected) Color.White else TealAccent)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Holiday banner if selected day has holiday
        if (selectedHoliday != null) {
            item {
                Surface(
                    color = CoralUrgent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎉 مناسبت رسمی: ${selectedHoliday.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = CoralUrgent
                        )
                    }
                }
            }
        }

        // Selected Day Reminders Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "کارهای ${uiState.selectedCalendarDate.formatFullDate()}:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Button(
                    onClick = {
                        val trigger = PersianDateEngine.jalaliToTimestamp(
                            uiState.selectedCalendarDate.year,
                            uiState.selectedCalendarDate.month,
                            uiState.selectedCalendarDate.day,
                            10,
                            0
                        )
                        viewModel.openAddDialog(
                            null // Create new for this day
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ثبت در این روز", fontSize = 12.sp)
                }
            }
        }

        // Reminders for selected day
        if (dayReminders.isEmpty()) {
            item {
                PersianEmptyState(
                    icon = Icons.Filled.EventNote,
                    title = "هیچ کاری در این تاریخ ثبت نشده",
                    description = "برای این روز می‌توانید یادآوری جدید تنظیم فرمایید."
                )
            }
        } else {
            items(dayReminders, key = { it.id }) { reminder ->
                ReminderItemCard(
                    reminder = reminder,
                    onToggleComplete = { viewModel.toggleComplete(reminder) },
                    onEdit = { viewModel.openAddDialog(reminder) },
                    onDelete = { viewModel.deleteReminder(reminder) },
                    onSnooze = { mins -> viewModel.rescheduleReminder(reminder, mins) }
                )
            }
        }
    }
}
