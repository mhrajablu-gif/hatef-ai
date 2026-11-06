package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val allReminders by viewModel.allReminders.collectAsState()
    val todayReminders by viewModel.todayReminders.collectAsState()
    val overdueReminders by viewModel.overdueReminders.collectAsState()

    val currentTimestamp = System.currentTimeMillis()
    val currentJalali = PersianDateEngine.timestampToJalali(currentTimestamp)
    val weekdayName = PersianDateEngine.getPersianWeekdayName(currentTimestamp)

    val categories = listOf("همه", "عمومی", "کاری", "شخصی", "سلامت", "خرید")

    val filteredList = allReminders.filter { reminder ->
        val matchesCategory = uiState.selectedCategory == null || reminder.category == uiState.selectedCategory
        val matchesSearch = uiState.searchQuery.isBlank() ||
                reminder.title.contains(uiState.searchQuery, ignoreCase = true) ||
                reminder.description.contains(uiState.searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "امروز، $weekdayName",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentJalali.formatFullDate(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.toggleTheme() },
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("theme_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (themeMode == com.example.ui.theme.ThemeMode.DARK) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    contentDescription = "تغییر حالت تم تاریک و روشن",
                                    tint = if (themeMode == com.example.ui.theme.ThemeMode.DARK) AmberWarning else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Button(
                                onClick = { viewModel.openAddDialog() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("add_manual_button")
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "افزودن دستی")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("کار جدید", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Conflict warning banner if any
            if (uiState.conflictNotice != null) {
                item {
                    Surface(
                        color = AmberWarning.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberWarning)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.conflictNotice ?: "",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearConflictNotice() }) {
                                Icon(Icons.Filled.Close, contentDescription = "بستن", tint = AmberWarning)
                            }
                        }
                    }
                }
            }

            // Quick Stats Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        title = "کارهای امروز",
                        value = PersianDateEngine.toPersianDigits(todayReminders.size),
                        color = TealAccent,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "انجام‌شده",
                        value = PersianDateEngine.toPersianDigits(allReminders.count { it.isCompleted }),
                        color = EmeraldSuccess,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "عقب‌افتاده",
                        value = PersianDateEngine.toPersianDigits(overdueReminders.size),
                        color = CoralUrgent,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Search and Category Filters
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("جست‌وجو در عنوان یا توضیحات یادآوری...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "جستجو") },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "پاک کردن")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_text_field")
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(categories) { cat ->
                            val isSelected = (cat == "همه" && uiState.selectedCategory == null) ||
                                    (uiState.selectedCategory == cat)
                            CategoryChip(
                                category = cat,
                                isSelected = isSelected,
                                onClick = {
                                    viewModel.setSelectedCategory(if (cat == "همه") null else cat)
                                }
                            )
                        }
                    }
                }
            }

            // Reminders List Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.selectedCategory != null) "یادآوری‌های دسته ${uiState.selectedCategory}" else "لیست یادآوری‌ها",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${PersianDateEngine.toPersianDigits(filteredList.size)} مورد",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Reminders Items
            if (filteredList.isEmpty()) {
                item {
                    PersianEmptyState(
                        icon = Icons.Filled.Today,
                        title = "هیچ یادآوری‌ای یافت نشد",
                        description = "با لمس دکمه میکروفون یا «کار جدید»، اولین برنامه خود را بسازید."
                    )
                }
            } else {
                items(filteredList, key = { it.id }) { reminder ->
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

        // Push-to-Talk Big Floating Action Bar at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "دستیار صوتی یادت",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "برای صحبت، دکمه میکروفون را لمس کنید",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                FloatingActionButton(
                    onClick = { viewModel.openVoiceSheet() },
                    containerColor = TealPrimary,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("open_voice_fab")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "بازکردن میکروفون دستیار هوشمند",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = color.copy(alpha = 0.08f),
        borderColor = color.copy(alpha = 0.25f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}
