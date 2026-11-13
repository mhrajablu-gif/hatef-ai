package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.PersianDateEngine
import java.util.Locale
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary

@Composable
fun WheelDrawerPicker(
    value: Int,
    range: List<Int>,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    formatItem: (Int) -> String = { PersianDateEngine.toPersianDigits(String.format(Locale.ROOT, "%02d", it)) }
) {
    val currentIndex = range.indexOf(value).coerceAtLeast(0)
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    fun step(delta: Int) {
        val nextIndex = (currentIndex + delta).mod(range.size)
        onValueChange(range[nextIndex])
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Roller drawer box
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .width(68.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(range, currentIndex) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            dragAccumulator += dragAmount
                            if (dragAccumulator > 32f) {
                                step(-1) // Dragged down -> previous
                                dragAccumulator = 0f
                            } else if (dragAccumulator < -32f) {
                                step(1)  // Dragged up -> next
                                dragAccumulator = 0f
                            }
                        },
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f }
                    )
                }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Arrow / Item Above
                val prevVal = range[(currentIndex - 1).mod(range.size)]
                IconButton(
                    onClick = { step(1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "افزایش $label",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Center Selected Item (Roller focal window)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(44.dp)
                        .background(
                            color = TealPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = 1.5.dp,
                            color = TealPrimary,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    Text(
                        text = formatItem(value),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                // Arrow / Item Below
                IconButton(
                    onClick = { step(-1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "کاهش $label",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MonthWheelDrawerPicker(
    selectedMonth: Int,
    onMonthChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val monthNames = PersianDateEngine.MONTH_NAMES
    val currentIndex = (selectedMonth - 1).coerceIn(0, 11)
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    fun step(delta: Int) {
        val nextIndex = (currentIndex + delta).mod(12)
        onMonthChange(nextIndex + 1)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "ماه",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .width(100.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(selectedMonth) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            dragAccumulator += dragAmount
                            if (dragAccumulator > 32f) {
                                step(-1)
                                dragAccumulator = 0f
                            } else if (dragAccumulator < -32f) {
                                step(1)
                                dragAccumulator = 0f
                            }
                        },
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f }
                    )
                }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { step(1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "ماه قبل",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(44.dp)
                        .background(
                            color = TealPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = 1.5.dp,
                            color = TealPrimary,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    Text(
                        text = monthNames.getOrElse(selectedMonth - 1) { "" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                IconButton(
                    onClick = { step(-1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "ماه بعد",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
