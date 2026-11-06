package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel
import java.util.Calendar

@Composable
fun ReportsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val allReminders by viewModel.allReminders.collectAsState()
    var selectedReportTab by remember { mutableIntStateOf(0) } // 0: Daily, 1: Weekly, 2: Monthly

    val reportTitles = listOf("روزانه (امروز)", "هفتگی", "ماهانه شمسی")

    val currentNow = System.currentTimeMillis()
    val todayJalali = PersianDateEngine.timestampToJalali(currentNow)

    // Compute subsets
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = cal.timeInMillis
    val endOfToday = startOfToday + 24 * 3600 * 1000L

    val startOfWeek = startOfToday - 6 * 24 * 3600 * 1000L

    // Start of Jalali month
    val startOfMonth = PersianDateEngine.jalaliToTimestamp(todayJalali.year, todayJalali.month, 1, 0, 0)
    val daysInThisMonth = PersianDateEngine.getDaysInMonth(todayJalali.year, todayJalali.month)
    val endOfMonth = startOfMonth + daysInThisMonth * 24 * 3600 * 1000L

    val currentList = when (selectedReportTab) {
        0 -> allReminders.filter { it.triggerTimestamp in startOfToday until endOfToday }
        1 -> allReminders.filter { it.triggerTimestamp in startOfWeek until endOfToday }
        else -> allReminders.filter { it.triggerTimestamp in startOfMonth until endOfMonth }
    }

    val stats = viewModel.calculateStats(currentList)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab selector
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                TabRow(
                    selectedTabIndex = selectedReportTab,
                    containerColor = Color.Transparent,
                    indicator = {}
                ) {
                    reportTitles.forEachIndexed { index, title ->
                        val isSelected = selectedReportTab == index
                        Tab(
                            selected = isSelected,
                            onClick = { selectedReportTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        )
                    }
                }
            }
        }

        // Summary Performance Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "میزان موفقیت و پیشرفت",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "${PersianDateEngine.toPersianDigits(stats.completionPercentage)}٪",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (stats.completionPercentage >= 70) EmeraldSuccess else TealPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { stats.completionPercentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = if (stats.completionPercentage >= 70) EmeraldSuccess else TealPrimary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        ReportPill(label = "کل کارها", count = stats.totalCount, color = MaterialTheme.colorScheme.onSurface)
                        ReportPill(label = "انجام‌شده", count = stats.completedCount, color = EmeraldSuccess)
                        ReportPill(label = "باقی‌مانده", count = stats.pendingCount, color = AmberWarning)
                        ReportPill(label = "عقب‌افتاده", count = stats.overdueCount, color = CoralUrgent)
                    }
                }
            }
        }

        // Weekly comparison chart (only in weekly tab)
        if (selectedReportTab == 1) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "توزیع کارها در روزهای هفته گذشته:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            for (i in 6 downTo 0) {
                                val dayMillis = startOfToday - i * 24 * 3600 * 1000L
                                val dayName = PersianDateEngine.getPersianWeekdayName(dayMillis).take(2)
                                val dayCount = allReminders.count {
                                    it.triggerTimestamp in dayMillis until (dayMillis + 24 * 3600 * 1000L)
                                }
                                val maxBar = 10.coerceAtLeast(currentList.size)
                                val heightFactor = (dayCount.toFloat() / maxBar).coerceIn(0.1f, 1f)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = PersianDateEngine.toPersianDigits(dayCount),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dayCount > 0) TealPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(18.dp)
                                            .height((80 * heightFactor).dp)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(if (dayCount > 0) TealAccent else MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = dayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Task Items Breakdown
        item {
            Text(
                text = "ریز کارهای این دوره:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (currentList.isEmpty()) {
            item {
                PersianEmptyState(
                    icon = Icons.Filled.Assessment,
                    title = "اطلاعاتی در این دوره ثبت نشده است",
                    description = "با ثبت کارها و انجام آن‌ها، نمودارهای بهره‌وری به‌روز می‌شوند."
                )
            }
        } else {
            items(currentList.size) { index ->
                val task = currentList[index]
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = if (task.isCompleted) EmeraldSuccess.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (task.isCompleted) Icons.Filled.CheckCircle else Icons.Filled.PendingActions,
                                contentDescription = null,
                                tint = if (task.isCompleted) EmeraldSuccess else AmberWarning,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${task.jalaliDateText} | ساعت ${task.timeText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }

                        PriorityBadge(task.priority)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPill(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = PersianDateEngine.toPersianDigits(count),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}
