package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.TealPrimary

@Composable
fun AddEditReminderDialog(
    initialReminder: ReminderEntity? = null,
    onDismiss: () -> Unit,
    onSave: (ReminderEntity) -> Unit
) {
    val currentJalali = if (initialReminder != null) {
        PersianDateEngine.timestampToJalali(initialReminder.triggerTimestamp)
    } else {
        PersianDateEngine.timestampToJalali(System.currentTimeMillis() + 60 * 60 * 1000L)
    }

    var title by remember { mutableStateOf(initialReminder?.title ?: "") }
    var description by remember { mutableStateOf(initialReminder?.description ?: "") }
    var selectedYear by remember { mutableIntStateOf(currentJalali.year) }
    var selectedMonth by remember { mutableIntStateOf(currentJalali.month) }
    var selectedDay by remember { mutableIntStateOf(currentJalali.day) }
    var selectedHour by remember { mutableIntStateOf(currentJalali.hour) }
    var selectedMinute by remember { mutableIntStateOf(currentJalali.minute) }

    var category by remember { mutableStateOf(initialReminder?.category ?: "عمومی") }
    var priority by remember { mutableIntStateOf(initialReminder?.priority ?: 0) }
    var repeatType by remember { mutableStateOf(initialReminder?.repeatType ?: "NONE") }
    var advanceAlertMinutes by remember { mutableIntStateOf(initialReminder?.advanceAlertMinutes ?: 0) }

    var titleError by remember { mutableStateOf(false) }

    val categories = listOf("عمومی", "کاری", "شخصی", "سلامت", "خرید")
    val repeatOptions = listOf("NONE" to "بدون تکرار", "DAILY" to "روزانه", "WEEKLY" to "هفتگی", "MONTHLY" to "ماهانه")
    val advanceOptions = listOf(0 to "همزمان", 5 to "۵ دقیقه قبل", 15 to "۱۵ دقیقه قبل", 30 to "نیم ساعت قبل", 60 to "۱ ساعت قبل")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (initialReminder != null) "ویرایش یادآوری" else "افزودن یادآوری جدید",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("عنوان یادآوری *") },
                    isError = titleError,
                    supportingText = {
                        if (titleError) Text("لطفاً عنوان را وارد فرمایید", color = CoralUrgent)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("add_dialog_title")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("توضیحات اختیاری") },
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("add_dialog_desc")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Date Picker Wheels Section (چرخنده کشویی تاریخ شمسی)
                Text(
                    text = "انتخاب کشویی تاریخ شمسی:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Day Wheel (1..31)
                    val daysInCurMonth = PersianDateEngine.getDaysInMonth(selectedYear, selectedMonth)
                    com.example.ui.components.WheelDrawerPicker(
                        value = selectedDay.coerceIn(1, daysInCurMonth),
                        range = (1..daysInCurMonth).toList(),
                        onValueChange = { selectedDay = it },
                        label = "روز"
                    )

                    // Month Wheel (Farvardin..Esfand)
                    com.example.ui.components.MonthWheelDrawerPicker(
                        selectedMonth = selectedMonth,
                        onMonthChange = { selectedMonth = it }
                    )

                    // Year Wheel (1403..1407)
                    com.example.ui.components.WheelDrawerPicker(
                        value = selectedYear,
                        range = listOf(1403, 1404, 1405, 1406, 1407),
                        onValueChange = { selectedYear = it },
                        label = "سال",
                        formatItem = { PersianDateEngine.toPersianDigits(it) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Time Picker Wheels Section (چرخنده کشویی ساعت و دقیقه)
                Text(
                    text = "انتخاب کشویی ساعت و دقیقه:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minute Wheel (0..59) — placed first so it appears on the right in RTL.
                    com.example.ui.components.WheelDrawerPicker(
                        value = selectedMinute,
                        range = (0..59).toList(),
                        onValueChange = { selectedMinute = it },
                        label = "دقیقه"
                    )

                    Text(
                        text = ":",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp)
                    )

                    // Hour Wheel (0..23) — appears on the left in RTL.
                    com.example.ui.components.WheelDrawerPicker(
                        value = selectedHour,
                        range = (0..23).toList(),
                        onValueChange = { selectedHour = it },
                        label = "ساعت"
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Priority selector
                Text(
                    text = "اولویت:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "عادی", 1 to "مهم", 2 to "فوری").forEach { (p, label) ->
                        Button(
                            onClick = { priority = p },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (priority == p) TealPrimary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                color = if (priority == p) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (priority == p) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Category selector
                Text(
                    text = "دسته‌بندی:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        CategoryChip(
                            category = cat,
                            isSelected = category == cat,
                            onClick = { category = cat }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Advance alert
                Text(
                    text = "هشدار زودهنگام:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    advanceOptions.take(3).forEach { (mins, lbl) ->
                        CategoryChip(
                            category = lbl,
                            isSelected = advanceAlertMinutes == mins,
                            onClick = { advanceAlertMinutes = mins }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("انصراف")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                titleError = true
                                return@Button
                            }

                            val maxDays = PersianDateEngine.getDaysInMonth(selectedYear, selectedMonth)
                            val validDay = selectedDay.coerceIn(1, maxDays)
                            val triggerMillis = PersianDateEngine.jalaliToTimestamp(
                                selectedYear,
                                selectedMonth,
                                validDay,
                                selectedHour,
                                selectedMinute
                            )
                            val jDateObj = PersianDateEngine.timestampToJalali(triggerMillis)
                            val jalaliStr = jDateObj.formatPersianNumeric()
                            val timeStr = PersianDateEngine.formatPersianTime(selectedHour, selectedMinute)

                            val toSave = (initialReminder ?: ReminderEntity(
                                title = title.trim(),
                                description = description.trim(),
                                triggerTimestamp = triggerMillis,
                                jalaliDateText = jalaliStr,
                                timeText = timeStr,
                                category = category,
                                priority = priority,
                                repeatType = repeatType,
                                advanceAlertMinutes = advanceAlertMinutes
                            )).copy(
                                title = title.trim(),
                                description = description.trim(),
                                triggerTimestamp = triggerMillis,
                                jalaliDateText = jalaliStr,
                                timeText = timeStr,
                                category = category,
                                priority = priority,
                                repeatType = repeatType,
                                advanceAlertMinutes = advanceAlertMinutes
                            )

                            onSave(toSave)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("save_reminder_btn")
                    ) {
                        Text("ذخیره یادآوری", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
