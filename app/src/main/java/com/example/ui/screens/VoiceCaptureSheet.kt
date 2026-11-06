package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.conversation.ActionCardType
import com.example.service.conversation.ChatMessage
import com.example.service.conversation.ConversationActionCard
import com.example.service.conversation.MessageSender
import com.example.service.speech.VoiceState
import com.example.ui.theme.CoralUrgent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val listState = rememberLazyListState()

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    // Google System Voice Intent launcher (Exact speech recognizer engine used by Gboard)
    val systemVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                viewModel.sendChatMessage(text)
            }
        }
    }

    var manualSpeechInput by remember { mutableStateOf("") }
    var isHoldingButton by remember { mutableStateOf(false) }
    var isTapRecordingActive by remember { mutableStateOf(false) }
    // 0: Push-to-Talk (نگه‌داشتن), 1: Tap-to-Talk (لمس برای شروع/پایان)
    var recordingMode by remember { mutableStateOf(1) }

    val isRecording = isHoldingButton || isTapRecordingActive || voiceState is VoiceState.Recording

    LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Success || voiceState is VoiceState.Error) {
            isTapRecordingActive = false
            isHoldingButton = false
        }
    }

    // Auto-scroll to bottom of chat when new message arrives
    LaunchedEffect(chatMessages.size, uiState.isAiParsing) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // Pulsing animation for recording button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (isTapRecordingActive) {
                isTapRecordingActive = false
                viewModel.stopPushToTalk()
            }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = "یادت",
                        tint = TealPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "یادت - دستیار هوشمند",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "گفتگوی هوشمند، مشاوره و مدیریت برنامه‌ها",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.clearConversation() },
                        modifier = Modifier.testTag("clear_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "شروع گفتگوی جدید",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.openVoiceLab()
                        },
                        modifier = Modifier.testTag("voice_lab_shortcut")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = "آزمایشگاه صدا",
                            tint = TealAccent
                        )
                    }

                    IconButton(onClick = {
                        if (isTapRecordingActive) {
                            isTapRecordingActive = false
                            viewModel.stopPushToTalk()
                        }
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "بستن")
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Conversation Chat Thread
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 340.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chatMessages, key = { it.id }) { msg ->
                    ChatBubbleItem(
                        message = msg,
                        onSuggestionClick = { reply ->
                            viewModel.sendChatMessage(reply)
                        },
                        onConfirmAction = { card ->
                            viewModel.confirmActionCard(card)
                        },
                        onCancelAction = { card ->
                            viewModel.cancelActionCard(card)
                        },
                        onSelectCandidate = { candidate ->
                            viewModel.selectCandidateReminder(candidate)
                        }
                    )
                }

                if (uiState.isAiParsing) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = TealPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "یادت در حال پردازش و استدلال...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic suggestions from latest assistant message
            val lastAssistantMsg = chatMessages.lastOrNull { it.sender == MessageSender.ASSISTANT }
            if (lastAssistantMsg != null && lastAssistantMsg.suggestedReplies.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    items(lastAssistantMsg.suggestedReplies) { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = TealPrimary.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TealPrimary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .clickable {
                                    viewModel.sendChatMessage(suggestion)
                                }
                                .testTag("quick_suggestion_chip")
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelSmall,
                                color = TealPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Real-time Voice State feedback
            when (val state = voiceState) {
                is VoiceState.Recording -> {
                    Text(
                        text = if (recordingMode == 0) "دکمه را نگه‌دارید و صحبت کنید..." else "در حال شنیدن... روی دکمه بزنید تا پایان یابد",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TealAccent
                    )
                    if (state.partialText.isNotBlank()) {
                        Text(
                            text = "«${state.partialText}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                is VoiceState.Processing -> {
                    Text(
                        text = "در حال پردازش گفتار...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is VoiceState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Input Bar: Microphone + System Mic + Text Input + Send
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Main Mic Button (Supports Push-to-Talk and Tap-to-Talk)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) MaterialTheme.colorScheme.error else TealPrimary
                        )
                        .pointerInput(recordingMode, hasAudioPermission) {
                            if (!hasAudioPermission) {
                                detectTapGestures {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else if (recordingMode == 0) {
                                // Push-to-Talk (Hold)
                                detectTapGestures(
                                    onPress = {
                                        isHoldingButton = true
                                        viewModel.startPushToTalk()
                                        val released = tryAwaitRelease()
                                        isHoldingButton = false
                                        viewModel.stopPushToTalk()
                                    }
                                )
                            } else {
                                // Tap-to-Talk (Toggle)
                                detectTapGestures {
                                    if (isTapRecordingActive) {
                                        isTapRecordingActive = false
                                        viewModel.stopPushToTalk()
                                    } else {
                                        isTapRecordingActive = true
                                        viewModel.startPushToTalk()
                                    }
                                }
                            }
                        }
                        .testTag("voice_chat_mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "میکروفون",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Shortcut to Google System Voice Recognizer dialog (Gboard engine)
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان یا پیام خود را بگویید...")
                        }
                        try {
                            systemVoiceLauncher.launch(intent)
                        } catch (e: Exception) {
                            // If system dialog not found, start in-app
                            viewModel.startPushToTalk()
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .testTag("system_gboard_mic_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.VoiceChat,
                        contentDescription = "تایپ صوتی سیستم / Gboard",
                        tint = TealPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Text Input Field (Standard IME with Gboard voice typing support)
                OutlinedTextField(
                    value = manualSpeechInput,
                    onValueChange = { manualSpeechInput = it },
                    placeholder = { Text("پیام یا فرمان خود را بنویسید...", fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_text_input"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                        autoCorrectEnabled = true
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = manualSpeechInput.trim()
                            if (text.isNotBlank()) {
                                manualSpeechInput = ""
                                viewModel.sendChatMessage(text)
                            }
                        }
                    ),
                    trailingIcon = {
                        if (manualSpeechInput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val text = manualSpeechInput.trim()
                                    manualSpeechInput = ""
                                    viewModel.sendChatMessage(text)
                                },
                                modifier = Modifier.testTag("chat_send_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "ارسال",
                                    tint = TealPrimary
                                )
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Switcher: Tap vs Hold Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (recordingMode == 1) "حالت شنود: لمسی (ضربه برای شروع/پایان)" else "حالت شنود: فشاری (نگه‌داشتن حین صحبت)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تغییر حالت",
                    style = MaterialTheme.typography.labelSmall,
                    color = TealPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        recordingMode = if (recordingMode == 1) 0 else 1
                    }
                )
            }
        }
    }
}

@Composable
fun ChatBubbleItem(
    message: ChatMessage,
    onSuggestionClick: (String) -> Unit,
    onConfirmAction: (ConversationActionCard) -> Unit,
    onCancelAction: (ConversationActionCard) -> Unit,
    onSelectCandidate: (com.example.data.model.ReminderEntity) -> Unit
) {
    val isUser = message.sender == MessageSender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(TealPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = "یادت",
                        tint = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Column(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) TealPrimary else MaterialTheme.colorScheme.surface
                    )
                    .border(
                        width = 1.dp,
                        color = if (isUser) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )

                // Embedded Action Card
                message.actionCard?.let { card ->
                    Spacer(modifier = Modifier.height(8.dp))
                    when (card.type) {
                        ActionCardType.PREVIEW_NEW_REMINDER -> {
                            card.task?.let { task ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "عنوان: «${task.title}»",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Event,
                                                contentDescription = null,
                                                tint = TealAccent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = task.jalaliDateText,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Icon(
                                                imageVector = Icons.Filled.Schedule,
                                                contentDescription = null,
                                                tint = TealAccent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = task.timeText,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = { onCancelAction(card) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text(card.secondaryActionLabel ?: "لغو", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { onConfirmAction(card) },
                                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(card.primaryActionLabel, fontSize = 12.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ActionCardType.SELECT_FROM_MULTIPLE_REMINDERS -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                card.candidateReminders.forEachIndexed { index, candidate ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectCandidate(candidate) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${index + 1}.",
                                                fontWeight = FontWeight.Bold,
                                                color = TealPrimary,
                                                fontSize = 14.sp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = candidate.title,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = "${candidate.jalaliDateText} ساعت ${candidate.timeText}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "انتخاب",
                                                tint = TealPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ActionCardType.CONFIRM_DELETE_REMINDER -> {
                            card.targetReminder?.let { rem ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = CoralUrgent.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "حذف قرار: «${rem.title}»",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CoralUrgent
                                        )
                                        Text(
                                            text = "${rem.jalaliDateText} ساعت ${rem.timeText}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = { onCancelAction(card) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text(card.secondaryActionLabel ?: "انصراف", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { onConfirmAction(card) },
                                                colors = ButtonDefaults.buttonColors(containerColor = CoralUrgent),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(card.primaryActionLabel, fontSize = 12.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ActionCardType.EXECUTION_RESULT -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(EmeraldSuccess.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = EmeraldSuccess,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = card.statusMessage ?: "عملیات با موفقیت انجام شد.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldSuccess,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
