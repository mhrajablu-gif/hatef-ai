package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MintLight
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    borderColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.border(BorderStroke(1.dp, borderColor), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        content()
    }
}

@Composable
fun PriorityBadge(priority: Int) {
    val (label, bg, fg) = when (priority) {
        2 -> Triple("فوری", CoralUrgent.copy(alpha = 0.15f), CoralUrgent)
        1 -> Triple("مهم", AmberWarning.copy(alpha = 0.15f), AmberWarning)
        else -> Triple("عادی", TealPrimary.copy(alpha = 0.12f), TealPrimary)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun CategoryChip(
    category: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        label = "chipBg"
    )
    val textCol = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("category_chip_$category")
    ) {
        Text(
            text = category,
            color = textCol,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun ReminderItemCard(
    reminder: ReminderEntity,
    onToggleComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOverdue = reminder.isOverdue
    val borderColor = when {
        reminder.isCompleted -> EmeraldSuccess.copy(alpha = 0.4f)
        isOverdue -> CoralUrgent.copy(alpha = 0.4f)
        reminder.priority == 2 -> CoralUrgent.copy(alpha = 0.3f)
        reminder.priority == 1 -> AmberWarning.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reminder_card_${reminder.id}"),
        borderColor = borderColor
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onToggleComplete,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("toggle_complete_${reminder.id}")
                    ) {
                        Icon(
                            imageVector = if (reminder.isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = if (reminder.isCompleted) "انجام شده" else "انجام نشده",
                            tint = if (reminder.isCompleted) EmeraldSuccess else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        Text(
                            text = reminder.title,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (reminder.isCompleted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (reminder.description.isNotBlank() && reminder.description != reminder.title) {
                            Text(
                                text = reminder.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityBadge(reminder.priority)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = reminder.category,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Time & Actions row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AccessTime,
                        contentDescription = "زمان",
                        tint = if (isOverdue) CoralUrgent else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${reminder.jalaliDateText} | ${reminder.timeText}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverdue) CoralUrgent else MaterialTheme.colorScheme.primary
                    )
                    if (isOverdue) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(عقب‌افتاده)",
                            style = MaterialTheme.typography.labelSmall,
                            color = CoralUrgent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!reminder.isCompleted) {
                        IconButton(
                            onClick = { onSnooze(15) },
                            modifier = Modifier.size(32.dp).testTag("snooze_btn_${reminder.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreTime,
                                contentDescription = "تعویق ۱۵ دقیقه",
                                tint = TealAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp).testTag("edit_btn_${reminder.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "ویرایش",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp).testTag("delete_btn_${reminder.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف",
                            tint = CoralUrgent.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PersianEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
