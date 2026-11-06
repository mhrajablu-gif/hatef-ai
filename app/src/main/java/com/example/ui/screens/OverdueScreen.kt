package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun OverdueScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val overdueReminders by viewModel.overdueReminders.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CoralUrgent.copy(alpha = 0.08f),
                borderColor = CoralUrgent.copy(alpha = 0.25f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "یادآوری‌های عقب‌افتاده",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = CoralUrgent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "کارهایی که موعد آن‌ها گذشته است ولی هنوز انجام نشده‌اند",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CoralUrgent.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${PersianDateEngine.toPersianDigits(overdueReminders.size)} کار",
                            color = CoralUrgent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (overdueReminders.isEmpty()) {
            item {
                PersianEmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "عالی است! هیچ کار عقب‌افتاده‌ای ندارید",
                    description = "تمامی برنامه‌های موعد گذشته انجام شده‌اند یا برای آینده تنظیم شده‌اند."
                )
            }
        } else {
            items(overdueReminders, key = { it.id }) { reminder ->
                OverdueItemCard(
                    reminder = reminder,
                    onComplete = { viewModel.toggleComplete(reminder) },
                    onReschedule15m = { viewModel.rescheduleReminder(reminder, 15) },
                    onReschedule1h = { viewModel.rescheduleReminder(reminder, 60) },
                    onRescheduleTomorrow = { viewModel.rescheduleReminder(reminder, 24 * 60) },
                    onDelete = { viewModel.deleteReminder(reminder) }
                )
            }
        }
    }
}

@Composable
private fun OverdueItemCard(
    reminder: ReminderEntity,
    onComplete: () -> Unit,
    onReschedule15m: () -> Unit,
    onReschedule1h: () -> Unit,
    onRescheduleTomorrow: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overdue_item_${reminder.id}"),
        borderColor = CoralUrgent.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                PriorityBadge(reminder.priority)
            }

            if (reminder.description.isNotBlank() && reminder.description != reminder.title) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reminder.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "موعد قبلی: ${reminder.jalaliDateText} ساعت ${reminder.timeText}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = CoralUrgent
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quick rescheduling & completion actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onComplete,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("انجام شد", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onReschedule15m,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+۱۵ دقیقه", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onReschedule1h,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+۱ ساعت", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onRescheduleTomorrow,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Text("فردا", fontSize = 11.sp)
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = CoralUrgent)
                }
            }
        }
    }
}
