package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.data.repository.ReminderRepository
import com.example.data.repository.TaskStats
import com.example.service.ai.GeminiSemanticParser
import com.example.service.ai.LocalPersianParser
import com.example.service.ai.ParsedTask
import com.example.service.alarm.AlarmScheduler
import com.example.service.speech.SpeechManager
import com.example.service.speech.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

enum class NavTab(val title: String) {
    HOME("داشبورد"),
    CALENDAR("تقویم"),
    ASSISTANT("دستیار هوشمند"),
    OVERDUE("عقب‌افتاده"),
    SETTINGS("تنظیمات")
}

enum class ClarificationType {
    MISSING_TIME_OR_DATE,
    MISSING_TITLE
}

data class ClarificationPrompt(
    val type: ClarificationType,
    val question: String,
    val tentativeTask: ParsedTask,
    val quickSuggestions: List<String>
)

data class MainUiState(
    val currentTab: NavTab = NavTab.HOME,
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val selectedCalendarDate: PersianDateEngine.JalaliDate = PersianDateEngine.timestampToJalali(System.currentTimeMillis()),
    val isAddEditOpen: Boolean = false,
    val editingReminder: ReminderEntity? = null,
    val isVoiceSheetOpen: Boolean = false,
    val parsedTasksPreview: List<ParsedTask> = emptyList(),
    val isAiParsing: Boolean = false,
    val conflictNotice: String? = null,
    val infoMessage: String? = null,
    val activeClarification: ClarificationPrompt? = null,
    val isVoiceLabOpen: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private fun com.example.service.conversation.ChatMessage.toEntity() =
        com.example.data.model.ChatMessageEntity(
            id = id,
            sender = sender.name,
            messageText = text,
            timestamp = timestamp,
            suggestedReplies = suggestedReplies.joinToString(CHAT_SUGGESTIONS_SEPARATOR)
        )

    companion object {
        private const val CHAT_SUGGESTIONS_SEPARATOR = "\u001F"
    }


    private val db = AppDatabase.getDatabase(application)
    private val repository = ReminderRepository(db.reminderDao())
    private val chatMessageDao = db.chatMessageDao()
    private val speechManager = SpeechManager(application)
    private val aiParser = GeminiSemanticParser()
    private val themePreferences = com.example.ui.theme.ThemePreferences(application)
    private val ttsSpeaker = com.example.service.speech.TtsSpeaker(application)

    // Conversational Agent & Tool Registry
    private val toolRegistry = com.example.service.conversation.AssistantToolRegistry(application, repository)
    private val conversationalEngine = com.example.service.conversation.ConversationalAiEngine(toolRegistry)
    // Sequential queue: each independently parsed task is clarified and confirmed on its own.
    private val pendingMultiReminderTasks = PendingReminderQueue()

    private val _conversationState = MutableStateFlow(com.example.service.conversation.ConversationState())
    val conversationState: StateFlow<com.example.service.conversation.ConversationState> = _conversationState.asStateFlow()

    private val initialWelcomeMessage = com.example.service.conversation.ChatMessage(
        sender = com.example.service.conversation.MessageSender.ASSISTANT,
        text = "سلام! من «یادت»، دستیار هوشمند شما هستم. می‌توانید هر سوالی درباره موضوعات مختلف (علمی، برنامه‌ریزی، فناوری و روزمره) بپرسید، یا دستوراتی مثل تنظیم، تغییر، جابجایی و حذف یادآورها («همه یادآورهام رو حذف کن») و تغییر تنظیمات برنامه را به من بگویید.",
        suggestedReplies = listOf(
            "همه یادآورهام رو حذف کن",
            "فردا ساعت ۱۰ یادم بنداز برم بانک",
            "پوسته رو تاریک کن",
            "تکنیک پومودورو چیه؟"
        )
    )

    private val _chatMessages = MutableStateFlow<List<com.example.service.conversation.ChatMessage>>(listOf(initialWelcomeMessage))
    val chatMessages: StateFlow<List<com.example.service.conversation.ChatMessage>> = _chatMessages.asStateFlow()

    val themeMode: StateFlow<com.example.ui.theme.ThemeMode> = themePreferences.themeMode

    init {
        // Restore the offline transcript before accepting new chat turns, then persist each
        // subsequent snapshot. The database migration is additive and leaves reminders intact.
        viewModelScope.launch {
            val savedMessages = chatMessageDao.getAllMessages()
            if (savedMessages.isNotEmpty()) {
                _chatMessages.value = savedMessages.map { saved ->
                    com.example.service.conversation.ChatMessage(
                        id = saved.id,
                        sender = runCatching {
                            com.example.service.conversation.MessageSender.valueOf(saved.sender)
                        }.getOrDefault(com.example.service.conversation.MessageSender.ASSISTANT),
                        text = saved.messageText,
                        timestamp = saved.timestamp,
                        suggestedReplies = saved.suggestedReplies
                            .split(CHAT_SUGGESTIONS_SEPARATOR)
                            .filter { it.isNotBlank() }
                    )
                }
            } else {
                chatMessageDao.insertAll(listOf(initialWelcomeMessage.toEntity()))
            }

            _chatMessages.collect { messages ->
                chatMessageDao.replaceAll(messages.map { it.toEntity() })
            }
        }

        viewModelScope.launch {
            speechManager.voiceState.collect { state ->
                if (state is VoiceState.Success && state.recognizedText.isNotBlank()) {
                    val recognized = state.recognizedText
                    speechManager.resetState()
                    if (_uiState.value.currentTab == NavTab.ASSISTANT || _uiState.value.isVoiceSheetOpen) {
                        // Assistant-tab speech must enter the same visible chat pipeline as typed input.
                        sendChatMessage(recognized)
                    } else if (_uiState.value.activeClarification != null) {
                        provideClarification(recognized)
                    } else {
                        parseSpokenCommand(recognized)
                    }
                }
            }
        }
    }

    fun setThemeMode(mode: com.example.ui.theme.ThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    fun toggleTheme() {
        themePreferences.toggleDarkLight()
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val voiceState: StateFlow<VoiceState> = speechManager.voiceState

    val allReminders: StateFlow<List<ReminderEntity>> = repository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayReminders: StateFlow<List<ReminderEntity>> = repository.allReminders.combine(_uiState) { list, _ ->
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = cal.timeInMillis
        list.filter { it.triggerTimestamp in start until end }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overdueReminders: StateFlow<List<ReminderEntity>> = repository.allReminders.combine(_uiState) { list, _ ->
        val now = System.currentTimeMillis()
        list.filter { !it.isCompleted && it.triggerTimestamp < now }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val calendarDayReminders: StateFlow<List<ReminderEntity>> = repository.allReminders.combine(_uiState) { list, state ->
        val j = state.selectedCalendarDate
        val (gY, gM, gD) = PersianDateEngine.jalaliToGregorian(j.year, j.month, j.day)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, gY)
            set(Calendar.MONTH, gM - 1)
            set(Calendar.DAY_OF_MONTH, gD)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = cal.timeInMillis
        list.filter { it.triggerTimestamp in start until end }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTab(tab: NavTab) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSelectedCategory(cat: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = cat)
    }

    fun selectCalendarDate(date: PersianDateEngine.JalaliDate) {
        _uiState.value = _uiState.value.copy(selectedCalendarDate = date)
    }

    fun openAddDialog(reminder: ReminderEntity? = null) {
        _uiState.value = _uiState.value.copy(
            isAddEditOpen = true,
            editingReminder = reminder
        )
    }

    fun closeAddDialog() {
        _uiState.value = _uiState.value.copy(
            isAddEditOpen = false,
            editingReminder = null
        )
    }

    fun openVoiceSheet() {
        speechManager.resetState()
        _uiState.value = _uiState.value.copy(
            isVoiceSheetOpen = true,
            parsedTasksPreview = emptyList(),
            conflictNotice = null
        )
    }

    fun closeVoiceSheet() {
        speechManager.cancelListening()
        _uiState.value = _uiState.value.copy(
            isVoiceSheetOpen = false,
            parsedTasksPreview = emptyList(),
            isAiParsing = false
        )
    }

    fun openVoiceLab() {
        speechManager.cancelListening()
        _uiState.value = _uiState.value.copy(isVoiceLabOpen = true)
    }

    fun closeVoiceLab() {
        _uiState.value = _uiState.value.copy(isVoiceLabOpen = false)
    }

    // Push-to-Talk operations
    fun startPushToTalk() {
        speechManager.startListening()
    }

    fun stopPushToTalk() {
        speechManager.stopListening()
    }

    /**
     * Sends a user turn in the multi-turn conversational AI thread.
     */
    fun sendChatMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || _uiState.value.isAiParsing) return

        val userMsg = com.example.service.conversation.ChatMessage(
            sender = com.example.service.conversation.MessageSender.USER,
            text = clean
        )
        val updatedHistory = _chatMessages.value + userMsg
        _chatMessages.value = updatedHistory

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiParsing = true)
            try {
                // Never collapse a multi-task message into the engine's single draft slot.
                // Queue every extracted task, including incomplete ones, and process them one by one.
                if (!_conversationState.value.isWaitingForAnswer()) {
                    val multiTasks = LocalPersianParser.parse(clean)
                    val isReminderRequest = conversationalEngine.shouldRouteAsReminderAction(clean)
                    if (multiTasks.size > 1 && isReminderRequest) {
                        pendingMultiReminderTasks.clear()
                        pendingMultiReminderTasks.addAll(multiTasks)
                        presentNextMultiReminder()
                        return@launch
                    }
                }

                // Keep the draft identity from before slot filling so a text confirmation
                // can advance only the matching queued reminder.
                val draftBeforeTurn = _conversationState.value.draftTask
                // Outer guard covers the complete intent-processing turn, not only the Gemini call.
                val response = kotlinx.coroutines.withTimeout(22_000L) {
                    conversationalEngine.processTurn(
                        userInput = clean,
                        currentState = _conversationState.value,
                        recentHistory = updatedHistory
                    )
                }

                _conversationState.value = response.newState
                // Slot filling returns a copied ParsedTask. Keep the queue head in sync
                // so confirming the clarified card advances to the next independent task.
                if (response.newState.activeIntent == com.example.service.conversation.ConversationIntent.CREATE_REMINDER) {
                    response.newState.draftTask?.let { pendingMultiReminderTasks.replaceFirst(it) }
                }
                val assistantMsg = com.example.service.conversation.ChatMessage(
                    sender = com.example.service.conversation.MessageSender.ASSISTANT,
                    text = response.replyText,
                    actionCard = response.actionCard,
                    suggestedReplies = response.suggestedReplies
                )
                _chatMessages.value = _chatMessages.value + assistantMsg
                ttsSpeaker.speak(response.replyText)

                val completedByText = response.executedResult?.success == true ||
                    response.newState.slotStatus == com.example.service.conversation.SlotFillingStatus.CANCELLED
                if (completedByText && draftBeforeTurn != null) {
                    val resultMessage = response.executedResult?.message ?: "یادآور «${draftBeforeTurn.title}» لغو شد."
                    val resultCard = com.example.service.conversation.ConversationActionCard(
                        type = com.example.service.conversation.ActionCardType.EXECUTION_RESULT,
                        task = draftBeforeTurn,
                        targetReminder = response.executedResult?.data as? ReminderEntity,
                        statusMessage = resultMessage
                    )
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.actionCard?.type == com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER &&
                            message.actionCard.task == draftBeforeTurn
                        ) message.copy(actionCard = resultCard) else message
                    }
                    if (pendingMultiReminderTasks.removeFirstIfSameSource(draftBeforeTurn)) {
                        presentNextMultiReminder()
                    }
                }

                if (response.executedResult != null && response.executedResult.success) {
                    _uiState.value = _uiState.value.copy(
                        infoMessage = response.executedResult.message
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("MainViewModel", "Chat turn timed out", e)
                _chatMessages.value = _chatMessages.value + com.example.service.conversation.ChatMessage(
                    sender = com.example.service.conversation.MessageSender.ASSISTANT,
                    text = "پاسخ‌گویی در زمان تعیین‌شده انجام نشد. لطفاً دوباره تلاش کنید؛ اگر تکرار شد، اتصال یا سرویس هوش مصنوعی را بررسی می‌کنیم."
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Chat turn failed", e)
                val errorMessage = when {
                    e is kotlinx.coroutines.TimeoutCancellationException ->
                        "پاسخ‌گویی بیش از حد طول کشید و درخواست متوقف شد. لطفاً دوباره تلاش کنید."
                    else ->
                        "در پردازش پیام مشکلی پیش آمد. لطفاً دوباره تلاش کنید."
                }
                _chatMessages.value = _chatMessages.value + com.example.service.conversation.ChatMessage(
                    sender = com.example.service.conversation.MessageSender.ASSISTANT,
                    text = errorMessage
                )
            } finally {
                _uiState.value = _uiState.value.copy(isAiParsing = false)
            }
        }
    }

    /**
     * Shows the next queued reminder as a clarification or confirmation.
     * The queue advances only after successful saving or explicit cancellation.
     */
    private fun presentNextMultiReminder() {
        val task = pendingMultiReminderTasks.firstOrNull() ?: run {
            _conversationState.value = com.example.service.conversation.ConversationState()
            return
        }

        val awaitingField: com.example.service.conversation.AwaitingField
        val question: String
        val suggestions: List<String>
        when {
            task.isAmbiguous || task.hasAmbiguousTimePeriod -> {
                awaitingField = com.example.service.conversation.AwaitingField.AMBIGUOUS_HOUR_AM_PM
                val hour = task.timeText.substringBefore(":")
                question = task.clarificationQuestion ?: "برای «${task.title}» منظورتان ساعت $hour صبح است یا عصر؟"
                suggestions = listOf("صبح", "عصر")
            }
            !task.hasExplicitTitle -> {
                awaitingField = com.example.service.conversation.AwaitingField.TITLE
                question = if (task.hasExplicitDate && task.hasExplicitTime) {
                    "برای ${task.jalaliDateText} ساعت ${task.timeText} چه کاری را باید یادتان بیندازم؟"
                } else {
                    "برای این یادآوری چه کاری را باید ثبت کنم؟"
                }
                suggestions = listOf("جلسه کاری", "تماس تلفنی", "رفتن به بانک")
            }
            !task.hasExplicitDate || !task.hasExplicitTime -> {
                awaitingField = com.example.service.conversation.AwaitingField.TIME_OR_DATE
                question = when {
                    !task.hasExplicitDate && !task.hasExplicitTime ->
                        "برای «${task.title}» چه روز و ساعتی تنظیم کنم؟"
                    !task.hasExplicitDate ->
                        "ساعت مشخص است؛ تاریخ یادآوری «${task.title}» چه روزی باشد؟"
                    else ->
                        "تاریخ مشخص است؛ یادآوری «${task.title}» را چه ساعتی تنظیم کنم؟"
                }
                suggestions = when {
                    !task.hasExplicitDate && !task.hasExplicitTime -> listOf("فردا ساعت ۹ صبح", "امروز ساعت ۱۸", "نیم ساعت دیگر")
                    !task.hasExplicitDate -> listOf("امروز", "فردا", "پس‌فردا")
                    else -> listOf("ساعت ۹ صبح", "ساعت ۱۸", "نیم ساعت دیگر")
                }
            }
            else -> {
                awaitingField = com.example.service.conversation.AwaitingField.EXECUTION_CONFIRMATION
                question = "یادآور «${task.title}» برای ${task.jalaliDateText} ساعت ${task.timeText} ثبت شود؟"
                suggestions = listOf("آره، ثبت کن", "لغو", "تغییر زمان")
            }
        }

        val ready = awaitingField == com.example.service.conversation.AwaitingField.EXECUTION_CONFIRMATION
        _conversationState.value = com.example.service.conversation.ConversationState(
            activeIntent = com.example.service.conversation.ConversationIntent.CREATE_REMINDER,
            slotStatus = if (ready) com.example.service.conversation.SlotFillingStatus.AWAITING_CONFIRMATION
                else com.example.service.conversation.SlotFillingStatus.COLLECTING_SLOTS,
            awaitingField = awaitingField,
            lastAssistantQuestion = question,
            draftTask = task
        )
        val message = com.example.service.conversation.ChatMessage(
            sender = com.example.service.conversation.MessageSender.ASSISTANT,
            text = question,
            actionCard = if (ready) com.example.service.conversation.ConversationActionCard(
                type = com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER,
                task = task,
                primaryActionLabel = "آره، ثبت کن",
                secondaryActionLabel = "لغو"
            ) else null,
            suggestedReplies = suggestions
        )
        _chatMessages.value = _chatMessages.value + message
        ttsSpeaker.speak(question)
    }

    fun clearConversation() {
        pendingMultiReminderTasks.clear()
        _conversationState.value = com.example.service.conversation.ConversationState()
        _chatMessages.value = listOf(initialWelcomeMessage)
    }

    fun confirmActionCard(card: com.example.service.conversation.ConversationActionCard) {
        viewModelScope.launch {
            when (card.type) {
                com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER -> {
                    card.task?.let { task ->
                        // A stale card must match the active draft as well as the queue head.
                        val activeDraft = _conversationState.value.draftTask
                        if (activeDraft == null || activeDraft != task) return@launch
                        if (pendingMultiReminderTasks.isNotEmpty() &&
                            !pendingMultiReminderTasks.isFirstSameSource(task)
                        ) return@launch
                        val result = toolRegistry.createReminder(task)
                        val confirmReply = if (result.success) {
                            result.message
                        } else {
                            "خطا در ایجاد یادآور: ${result.message}"
                        }
                        val entity = result.data as? ReminderEntity
                        val resultCard = card.copy(
                            type = if (result.success) {
                                com.example.service.conversation.ActionCardType.EXECUTION_RESULT
                            } else {
                                com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER
                            },
                            targetReminder = entity,
                            primaryActionLabel = if (result.success) "انجام شد" else "تلاش دوباره",
                            secondaryActionLabel = "لغو",
                            statusMessage = confirmReply
                        )
                        val pendingClarification = _conversationState.value.awaitingField !=
                            com.example.service.conversation.AwaitingField.NONE &&
                            _conversationState.value.awaitingField != com.example.service.conversation.AwaitingField.EXECUTION_CONFIRMATION &&
                            _conversationState.value.draftTask != null
                        _conversationState.value = _conversationState.value.copy(
                            slotStatus = if (pendingClarification) {
                                com.example.service.conversation.SlotFillingStatus.COLLECTING_SLOTS
                            } else if (result.success) {
                                com.example.service.conversation.SlotFillingStatus.COMPLETED
                            } else {
                                com.example.service.conversation.SlotFillingStatus.AWAITING_CONFIRMATION
                            },
                            awaitingField = if (pendingClarification) {
                                _conversationState.value.awaitingField
                            } else if (result.success) {
                                com.example.service.conversation.AwaitingField.NONE
                            } else {
                                com.example.service.conversation.AwaitingField.EXECUTION_CONFIRMATION
                            },
                            draftTask = if (pendingClarification) _conversationState.value.draftTask
                                else if (result.success) null else task,
                            lastAssistantQuestion = if (pendingClarification) _conversationState.value.lastAssistantQuestion else null,
                            lastMentionedReminderId = if (result.success) entity?.id else _conversationState.value.lastMentionedReminderId
                        )
                        // Convert the original preview card into a result card so it cannot be
                        // tapped again to create the same reminder twice.
                        _chatMessages.value = _chatMessages.value.map { message ->
                            if (message.actionCard == card) message.copy(actionCard = resultCard) else message
                        }
                        val assistantMsg = com.example.service.conversation.ChatMessage(
                            sender = com.example.service.conversation.MessageSender.ASSISTANT,
                            text = confirmReply,
                            suggestedReplies = if (result.success) listOf("یادآور جدید", "مشاهده یادآورها") else listOf("آره، دوباره ثبت کن", "لغو")
                        )
                        _chatMessages.value = _chatMessages.value + assistantMsg
                        ttsSpeaker.speak(confirmReply)
                        if (result.success && pendingMultiReminderTasks.removeFirstIfSameSource(task)) {
                            presentNextMultiReminder()
                        }
                    }
                }
                com.example.service.conversation.ActionCardType.CONFIRM_DELETE_REMINDER -> {
                    card.targetReminder?.let { rem ->
                        val res = toolRegistry.deleteOrCancelReminder(rem.id, isHardDelete = true)
                        val msg = com.example.service.conversation.ChatMessage(
                            sender = com.example.service.conversation.MessageSender.ASSISTANT,
                            text = res.message,
                            suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                        )
                        _chatMessages.value = _chatMessages.value + msg
                        ttsSpeaker.speak(res.message)
                        com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(getApplication())
                    }
                }
                else -> {}
            }
        }
    }

    fun selectCandidateReminder(candidate: ReminderEntity) {
        viewModelScope.launch {
            if (_conversationState.value.activeIntent == com.example.service.conversation.ConversationIntent.DELETE_REMINDER) {
                val res = toolRegistry.deleteOrCancelReminder(candidate.id, isHardDelete = true)
                _conversationState.value = _conversationState.value.copy(
                    awaitingField = com.example.service.conversation.AwaitingField.NONE,
                    candidateReminderIds = emptyList()
                )
                val msg = com.example.service.conversation.ChatMessage(
                    sender = com.example.service.conversation.MessageSender.ASSISTANT,
                    text = res.message,
                    suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                )
                _chatMessages.value = _chatMessages.value + msg
                ttsSpeaker.speak(res.message)
                com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(getApplication())
            } else {
                _conversationState.value = _conversationState.value.copy(
                    targetReminderId = candidate.id,
                    lastMentionedReminderId = candidate.id,
                    awaitingField = com.example.service.conversation.AwaitingField.NONE,
                    candidateReminderIds = emptyList()
                )
                val msg = com.example.service.conversation.ChatMessage(
                    sender = com.example.service.conversation.MessageSender.ASSISTANT,
                    text = "قرار «${candidate.title}» (${candidate.jalaliDateText} ساعت ${candidate.timeText}) انتخاب شد. چه تغییری مایلید اعمال شود؟",
                    suggestedReplies = listOf("نیم ساعت عقب بنداز", "حذفش کن", "ساعتش رو عوض کن")
                )
                _chatMessages.value = _chatMessages.value + msg
            }
        }
    }

    fun cancelActionCard(card: com.example.service.conversation.ConversationActionCard) {
        if (card.type == com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER) {
            val task = card.task ?: return
            val activeDraft = _conversationState.value.draftTask
            if (activeDraft == null || activeDraft != task) return
        }
        val hadQueuedTasks = pendingMultiReminderTasks.isNotEmpty()
        val cancelledQueuedTask = if (hadQueuedTasks) {
            card.task?.let { pendingMultiReminderTasks.removeFirstIfSameSource(it) } == true
        } else false
        // Ignore an old card after the queue has already advanced to another task.
        if (hadQueuedTasks && !cancelledQueuedTask) return

        _conversationState.value = _conversationState.value.copy(
            slotStatus = com.example.service.conversation.SlotFillingStatus.CANCELLED,
            awaitingField = com.example.service.conversation.AwaitingField.NONE,
            draftTask = null
        )
        val cancelledCard = card.copy(
            type = com.example.service.conversation.ActionCardType.EXECUTION_RESULT,
            primaryActionLabel = "لغو شد",
            secondaryActionLabel = null,
            statusMessage = card.task?.let { "یادآور «${it.title}» لغو شد." } ?: "عملیات لغو شد."
        )
        _chatMessages.value = _chatMessages.value.map { message ->
            if (message.actionCard == card) message.copy(actionCard = cancelledCard) else message
        }
        val cancelMsg = com.example.service.conversation.ChatMessage(
            sender = com.example.service.conversation.MessageSender.ASSISTANT,
            text = "عملیات لغو شد. کار دیگری هست که بتونم براتون انجام بدم؟",
            suggestedReplies = listOf("یادآور جدید", "یادآورهای امروز")
        )
        _chatMessages.value = _chatMessages.value + cancelMsg
        if (cancelledQueuedTask) presentNextMultiReminder()
    }

    fun parseSpokenCommand(text: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiParsing = true, activeClarification = null)
            try {
                val parsedList = aiParser.parseCommand(text)

            // Check if clarification is needed on any extracted task
            val taskNeedingClarification = parsedList.firstOrNull { it.needsClarification }
            if (taskNeedingClarification != null) {
                val missingDate = !taskNeedingClarification.hasExplicitDate &&
                    !taskNeedingClarification.hasResolvedRelativeDateTime
                val missingTime = !taskNeedingClarification.hasExplicitTime ||
                    taskNeedingClarification.hasAmbiguousTimePeriod
                val clarificationType = if (missingDate || missingTime) {
                    ClarificationType.MISSING_TIME_OR_DATE
                } else {
                    ClarificationType.MISSING_TITLE
                }

                val ambiguousHour = taskNeedingClarification.timeText.substringBefore(":")
                val question = when {
                    taskNeedingClarification.hasAmbiguousTimePeriod ->
                        "منظورتان ساعت $ambiguousHour صبح است یا شب؟"
                    missingDate && missingTime -> "تاریخ و ساعت یادآوری «${taskNeedingClarification.title}» مشخص نشده است؛ چه روز و ساعتی مدنظرتان است؟"
                    missingDate -> "ساعت مشخص است؛ تاریخ یادآوری «${taskNeedingClarification.title}» چه روزی باشد؟"
                    missingTime -> "تاریخ مشخص است؛ یادآوری «${taskNeedingClarification.title}» را چه ساعتی تنظیم کنم؟"
                    else -> "لطفاً عنوان یا موضوع این یادآوری را بفرمایید."
                }

                val suggestions = when {
                    taskNeedingClarification.hasAmbiguousTimePeriod ->
                        listOf("ساعت $ambiguousHour صبح", "ساعت $ambiguousHour شب")
                    missingDate && missingTime -> listOf("نیم ساعت دیگر", "امروز ساعت ۱۸:۰۰", "فردا ساعت ۹ صبح")
                    missingDate -> listOf("امروز", "فردا", "پس‌فردا", "سه روز دیگر")
                    missingTime -> listOf("ساعت ۹ صبح", "ساعت ۱۸:۰۰", "نیم ساعت دیگر")
                    else -> listOf("جلسه کاری", "تماس تلفنی", "خرید", "مصرف دارو")
                }

                val clarificationTask = if (taskNeedingClarification.hasAmbiguousTimePeriod) {
                    taskNeedingClarification.copy(
                        hasExplicitTime = false,
                        isAmbiguous = false,
                        clarificationQuestion = null,
                        analyzedResult = null
                    )
                } else {
                    taskNeedingClarification
                }
                val prompt = ClarificationPrompt(
                    type = clarificationType,
                    question = question,
                    tentativeTask = clarificationTask,
                    quickSuggestions = suggestions
                )

                _uiState.value = _uiState.value.copy(
                    isAiParsing = false,
                    activeClarification = prompt,
                    parsedTasksPreview = parsedList.filter { it != taskNeedingClarification }
                )
                ttsSpeaker.speak(question)
            } else {
                _uiState.value = _uiState.value.copy(
                    isAiParsing = false,
                    parsedTasksPreview = parsedList,
                    activeClarification = null
                )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Voice command parsing failed", e)
                _uiState.value = _uiState.value.copy(
                    activeClarification = null,
                    infoMessage = "در تحلیل فرمان صوتی مشکلی پیش آمد. لطفاً دوباره تلاش کنید."
                )
            } finally {
                _uiState.value = _uiState.value.copy(isAiParsing = false)
            }
        }
    }

    fun provideClarification(response: String) {
        val prompt = _uiState.value.activeClarification ?: return
        val cleanResponse = response.trim()
        if (cleanResponse.isBlank()) return

        val tentative = prompt.tentativeTask
        val updatedTask: ParsedTask = when (prompt.type) {
            ClarificationType.MISSING_TIME_OR_DATE -> {
                val extracted = PersianDateEngine.extractPersianTask(cleanResponse)
                val responseAnalysis = com.example.service.ai.PersianCommandAnalyzer.analyze(cleanResponse)
                if (responseAnalysis.validationStatus == com.example.service.ai.ValidationStatus.CONFLICT) {
                    val conflictPrompt = responseAnalysis.clarificationQuestion ?: "تاریخ‌های گفته‌شده با هم سازگار نیستند؛ لطفاً تاریخ را روشن کنید."
                    _uiState.value = _uiState.value.copy(
                        activeClarification = prompt.copy(question = conflictPrompt, quickSuggestions = responseAnalysis.quickSuggestions),
                        infoMessage = conflictPrompt
                    )
                    ttsSpeaker.speak(conflictPrompt)
                    return
                }
                val responseAmbiguousTime =
                    responseAnalysis.ambiguousFields.contains("HOUR_PERIOD_AM_PM")
                val resolvedRelativeDateTime = responseAnalysis.hasExplicitDate && responseAnalysis.hasExplicitTime && responseAnalysis.targetTimestamp != null
                val extractedHasDate = extracted.hasExplicitDate || responseAnalysis.hasExplicitDate || resolvedRelativeDateTime
                val hasDate = tentative.hasExplicitDate || extractedHasDate
                val responseHasTime = extracted.hasExplicitTime || responseAnalysis.hasExplicitTime || resolvedRelativeDateTime
                val hasTime = tentative.hasExplicitTime || (responseHasTime && !responseAmbiguousTime)

                val mergedCalendar = Calendar.getInstance().apply {
                    timeInMillis = tentative.triggerTimestamp
                }
                val answerCalendar = Calendar.getInstance().apply {
                    timeInMillis = responseAnalysis.targetTimestamp ?: extracted.triggerTimestamp
                }
                if (extractedHasDate) {
                    mergedCalendar.set(Calendar.YEAR, answerCalendar.get(Calendar.YEAR))
                    mergedCalendar.set(Calendar.MONTH, answerCalendar.get(Calendar.MONTH))
                    mergedCalendar.set(Calendar.DAY_OF_MONTH, answerCalendar.get(Calendar.DAY_OF_MONTH))
                }
                if (responseHasTime && !responseAmbiguousTime) {
                    mergedCalendar.set(Calendar.HOUR_OF_DAY, responseAnalysis.hour ?: answerCalendar.get(Calendar.HOUR_OF_DAY))
                    mergedCalendar.set(Calendar.MINUTE, responseAnalysis.minute ?: answerCalendar.get(Calendar.MINUTE))
                    mergedCalendar.set(Calendar.SECOND, 0)
                    mergedCalendar.set(Calendar.MILLISECOND, 0)
                }

                val mergedTimestamp = mergedCalendar.timeInMillis
                val j = PersianDateEngine.timestampToJalali(mergedTimestamp)
                val mergedTask = tentative.copy(
                    triggerTimestamp = mergedTimestamp,
                    jalaliDateText = j.formatPersianNumeric(),
                    timeText = PersianDateEngine.formatPersianTime(j.hour, j.minute),
                    hasExplicitTime = hasTime,
                    hasExplicitDate = hasDate,
                    clarificationQuestion = null,
                    isAmbiguous = false,
                    analyzedResult = null
                )

                if (responseAmbiguousTime || !hasDate || !hasTime) {
                    val ambiguousHour = extracted.timeText.substringBefore(":")
                    val followUp = when {
                        responseAmbiguousTime -> "منظورتان ساعت $ambiguousHour صبح است یا شب؟"
                        !hasDate && !hasTime -> "هنوز تاریخ و ساعت مشخص نشده است؛ لطفاً هر دو را بگویید، مثلاً «فردا ساعت ۹ صبح»."
                        !hasDate -> "ساعت ثبت شد؛ تاریخ یادآوری چه روزی باشد؟"
                        else -> "تاریخ ثبت شد؛ یادآوری را چه ساعتی تنظیم کنم؟"
                    }
                    val suggestions = when {
                        responseAmbiguousTime -> listOf("ساعت $ambiguousHour صبح", "ساعت $ambiguousHour شب")
                        !hasDate && !hasTime -> listOf("نیم ساعت دیگر", "امروز ساعت ۱۸:۰۰", "فردا ساعت ۹ صبح")
                        !hasDate -> listOf("امروز", "فردا", "پس‌فردا", "سه روز دیگر")
                        else -> listOf("ساعت ۹ صبح", "ساعت ۱۸:۰۰", "نیم ساعت دیگر")
                    }
                    _uiState.value = _uiState.value.copy(
                        activeClarification = prompt.copy(
                            question = followUp,
                            tentativeTask = mergedTask,
                            quickSuggestions = suggestions
                        ),
                        infoMessage = followUp
                    )
                    ttsSpeaker.speak(followUp)
                    return
                }

                if (!mergedTask.hasExplicitTitle) {
                    val titlePrompt = "زمان یادآوری مشخص شد؛ لطفاً عنوان یا موضوع آن را بفرمایید:"
                    _uiState.value = _uiState.value.copy(
                        activeClarification = ClarificationPrompt(
                            type = ClarificationType.MISSING_TITLE,
                            question = titlePrompt,
                            tentativeTask = mergedTask,
                            quickSuggestions = listOf("جلسه کاری", "تماس تلفنی", "خرید", "مصرف دارو")
                        ),
                        infoMessage = titlePrompt
                    )
                    ttsSpeaker.speak(titlePrompt)
                    return
                }
                mergedTask
            }
            ClarificationType.MISSING_TITLE -> {
                val cleanTitle = PersianDateEngine.extractPersianTask(cleanResponse).title
                val finalTitle = if (cleanTitle.isNotBlank() && !cleanTitle.startsWith("یادآوری ساعت")) cleanTitle else cleanResponse
                tentative.copy(
                    title = finalTitle,
                    hasExplicitTitle = true,
                    clarificationQuestion = null,
                    isAmbiguous = false
                )
            }
        }

        val currentPreview = _uiState.value.parsedTasksPreview.toMutableList()
        currentPreview.add(0, updatedTask)
        _uiState.value = _uiState.value.copy(
            activeClarification = null,
            parsedTasksPreview = currentPreview,
            infoMessage = "اطلاعات تکمیل شد: «${updatedTask.title}» برای ساعت ${updatedTask.timeText}"
        )
        ttsSpeaker.speak("یادآوری با موفقیت تنظیم شد.")
    }

    fun dismissClarification() {
        val prompt = _uiState.value.activeClarification ?: return
        _uiState.value = _uiState.value.copy(
            activeClarification = null,
            infoMessage = "یادآور «${prompt.tentativeTask.title}» به‌دلیل ناقص بودن اطلاعات ثبت نشد."
        )
    }

    private fun alarmScheduleMessage(
        result: com.example.service.alarm.AlarmScheduleResult,
        title: String
    ): String? = when (result) {
        com.example.service.alarm.AlarmScheduleResult.SCHEDULED -> null
        com.example.service.alarm.AlarmScheduleResult.PARTIALLY_SCHEDULED ->
            "یادآور «$title» ذخیره شد، اما پیش‌اعلان آن تنظیم نشد."
        com.example.service.alarm.AlarmScheduleResult.ALREADY_DUE ->
            "یادآور «$title» ذخیره شد، اما زمان انتخاب‌شده گذشته است؛ تاریخ و ساعت را اصلاح کن."
        com.example.service.alarm.AlarmScheduleResult.EXACT_ALARM_PERMISSION_REQUIRED ->
            "یادآور «$title» ذخیره شد، اما زنگ آن تنظیم نشد. مجوز «هشدار دقیق» را از تنظیمات برنامه فعال کن."
        com.example.service.alarm.AlarmScheduleResult.ALARM_SERVICE_UNAVAILABLE,
        com.example.service.alarm.AlarmScheduleResult.FAILED ->
            "یادآور «$title» ذخیره شد، اما زمان‌بندی زنگ ناموفق بود. تنظیمات مجوز هشدار را بررسی کن."
    }

    private fun buildClarificationPrompt(task: ParsedTask): ClarificationPrompt {
        val isAmbiguous = task.isAmbiguous || task.hasAmbiguousTimePeriod
        val missingDate = !task.hasExplicitDate && !task.hasResolvedRelativeDateTime
        val missingTime = !task.hasExplicitTime || isAmbiguous
        val type = if (missingDate || missingTime) ClarificationType.MISSING_TIME_OR_DATE else ClarificationType.MISSING_TITLE
        val hour = task.timeText.substringBefore(":")
        val question = when {
            isAmbiguous -> "منظورتان ساعت $hour صبح است یا شب؟"
            missingDate && missingTime -> "تاریخ و ساعت یادآوری «${task.title}» مشخص نشده است؛ چه روز و ساعتی مدنظرتان است؟"
            missingDate -> "ساعت مشخص است؛ تاریخ یادآوری «${task.title}» چه روزی باشد؟"
            missingTime -> "تاریخ مشخص است؛ یادآوری «${task.title}» را چه ساعتی تنظیم کنم؟"
            else -> "لطفاً عنوان یا موضوع این یادآوری را بفرمایید."
        }
        val suggestions = when {
            isAmbiguous -> listOf("ساعت $hour صبح", "ساعت $hour شب")
            missingDate && missingTime -> listOf("نیم ساعت دیگر", "امروز ساعت ۱۸:۰۰", "فردا ساعت ۹ صبح")
            missingDate -> listOf("امروز", "فردا", "پس‌فردا", "سه روز دیگر")
            missingTime -> listOf("ساعت ۹ صبح", "ساعت ۱۸:۰۰", "نیم ساعت دیگر")
            else -> listOf("جلسه کاری", "تماس تلفنی", "خرید", "مصرف دارو")
        }
        return ClarificationPrompt(type, question, task, suggestions)
    }

    fun confirmPreviewTask(task: ParsedTask) {
        if (!task.hasExplicitTitle || !task.hasExplicitDate || !task.hasExplicitTime || task.isAmbiguous || task.hasAmbiguousTimePeriod) {
            val prompt = buildClarificationPrompt(task)
            _uiState.value = _uiState.value.copy(
                activeClarification = prompt,
                parsedTasksPreview = _uiState.value.parsedTasksPreview.filter { it != task },
                infoMessage = "این یادآوری هنوز اطلاعات کامل ندارد و ذخیره نشد."
            )
            return
        }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val entity = ReminderEntity(
                title = task.title,
                description = task.description,
                triggerTimestamp = task.triggerTimestamp,
                jalaliDateText = task.jalaliDateText,
                timeText = task.timeText,
                category = task.category,
                priority = task.priority,
                repeatType = task.repeatType,
                recurrenceEndDate = task.recurrenceEndDate,
                recurrenceDaysOfWeek = task.recurrenceDaysOfWeek,
                advanceAlertMinutes = task.advanceAlertMinutes
            )

            // Check conflict
            val conflict = repository.findConflictingReminder(entity.triggerTimestamp)
            if (conflict != null) {
                _uiState.value = _uiState.value.copy(
                    conflictNotice = "توجه: با یادآوری «${conflict.title}» در ساعت ${conflict.timeText} تداخل زمانی وجود دارد."
                )
            }

            val id = repository.insert(entity)
            val scheduleMessage = alarmScheduleMessage(AlarmScheduler.scheduleReminder(context, entity.copy(id = id)), entity.title)

            // Remove confirmed from preview
            val updated = _uiState.value.parsedTasksPreview.filter { it != task }
            _uiState.value = _uiState.value.copy(
                parsedTasksPreview = updated,
                infoMessage = scheduleMessage ?: "یادآوری «${entity.title}» با موفقیت ثبت شد."
            )
            if (updated.isEmpty()) {
                _uiState.value = _uiState.value.copy(isVoiceSheetOpen = false)
            }
        }
    }

    fun confirmAllPreviewTasks() {
        viewModelScope.launch {
            val incompleteTask = _uiState.value.parsedTasksPreview.firstOrNull {
                !it.hasExplicitTitle || !it.hasExplicitDate || !it.hasExplicitTime || it.isAmbiguous || it.hasAmbiguousTimePeriod
            }
            if (incompleteTask != null) {
                _uiState.value = _uiState.value.copy(
                    activeClarification = buildClarificationPrompt(incompleteTask),
                    parsedTasksPreview = _uiState.value.parsedTasksPreview.filter { it != incompleteTask },
                    infoMessage = "یکی از یادآوری‌ها ناقص است؛ ابتدا اطلاعات آن را کامل کنیم. هیچ یادآوری ناقصی ذخیره نشد."
                )
                return@launch
            }
            val context = getApplication<Application>()
            val scheduleWarnings = mutableListOf<String>()
            for (task in _uiState.value.parsedTasksPreview) {
                val entity = ReminderEntity(
                    title = task.title,
                    description = task.description,
                    triggerTimestamp = task.triggerTimestamp,
                    jalaliDateText = task.jalaliDateText,
                    timeText = task.timeText,
                    category = task.category,
                    priority = task.priority,
                    repeatType = task.repeatType,
                    recurrenceEndDate = task.recurrenceEndDate,
                    recurrenceDaysOfWeek = task.recurrenceDaysOfWeek,
                    advanceAlertMinutes = task.advanceAlertMinutes
                )
                val id = repository.insert(entity)
                val scheduleMessage = alarmScheduleMessage(AlarmScheduler.scheduleReminder(context, entity.copy(id = id)), entity.title)
                if (scheduleMessage != null) scheduleWarnings += scheduleMessage
            }
            _uiState.value = _uiState.value.copy(
                parsedTasksPreview = emptyList(),
                isVoiceSheetOpen = false,
                infoMessage = if (scheduleWarnings.isEmpty()) "تمام یادآوری‌ها با موفقیت ثبت شدند." else scheduleWarnings.joinToString("\n")
            )
        }
    }

    fun removePreviewTask(index: Int) {
        val current = _uiState.value.parsedTasksPreview.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(parsedTasksPreview = current)
            if (current.isEmpty()) {
                _uiState.value = _uiState.value.copy(isVoiceSheetOpen = false)
            }
        }
    }

    fun saveReminder(reminder: ReminderEntity) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (reminder.id == 0L) {
                val id = repository.insert(reminder)
                val scheduleMessage = alarmScheduleMessage(AlarmScheduler.scheduleReminder(context, reminder.copy(id = id)), reminder.title)
                _uiState.value = _uiState.value.copy(infoMessage = scheduleMessage ?: "یادآوری جدید ثبت شد.")
            } else {
                repository.update(reminder)
                val scheduleMessage = alarmScheduleMessage(AlarmScheduler.scheduleReminder(context, reminder), reminder.title)
                _uiState.value = _uiState.value.copy(infoMessage = scheduleMessage ?: "تغییرات ذخیره شد.")
            }
            com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(context)
            closeAddDialog()
        }
    }

    fun toggleComplete(reminder: ReminderEntity) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val newStatus = !reminder.isCompleted
            repository.setCompleted(reminder.id, newStatus)
            if (newStatus) {
                AlarmScheduler.cancelReminder(context, reminder.id)
            } else {
                val scheduleMessage = alarmScheduleMessage(
                    AlarmScheduler.scheduleReminder(context, reminder.copy(isCompleted = false)), reminder.title
                )
                if (scheduleMessage != null) _uiState.value = _uiState.value.copy(infoMessage = scheduleMessage)
            }
            com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(context)
        }
    }

    fun deleteReminder(reminder: ReminderEntity) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            AlarmScheduler.cancelReminder(context, reminder.id)
            repository.delete(reminder)
            com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(context)
            _uiState.value = _uiState.value.copy(infoMessage = "یادآوری حذف شد.")
        }
    }

    fun rescheduleReminder(reminder: ReminderEntity, addedMinutes: Int) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val newMillis = System.currentTimeMillis() + addedMinutes * 60 * 1000L
            val j = PersianDateEngine.timestampToJalali(newMillis)
            val timeStr = PersianDateEngine.formatPersianTime(j.hour, j.minute)
            val dateStr = j.formatPersianNumeric()
            val updated = reminder.copy(
                triggerTimestamp = newMillis,
                jalaliDateText = dateStr,
                timeText = timeStr,
                isCompleted = false
            )
            repository.update(updated)
            val scheduleMessage = alarmScheduleMessage(AlarmScheduler.scheduleReminder(context, updated), updated.title)
            com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(context)
            _uiState.value = _uiState.value.copy(infoMessage = scheduleMessage ?: "یادآوری به ${addedMinutes} دقیقه بعد موکول شد.")
        }
    }

    fun clearInfoMessage() {
        _uiState.value = _uiState.value.copy(infoMessage = null)
    }

    fun clearConflictNotice() {
        _uiState.value = _uiState.value.copy(conflictNotice = null)
    }

    fun calculateStats(reminders: List<ReminderEntity>): TaskStats {
        val total = reminders.size
        val completed = reminders.count { it.isCompleted }
        val now = System.currentTimeMillis()
        val overdue = reminders.count { !it.isCompleted && it.triggerTimestamp < now }
        val pending = total - completed
        val pct = if (total > 0) ((completed.toDouble() / total) * 100).toInt() else 0
        return TaskStats(total, completed, overdue, pending, pct)
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        ttsSpeaker.destroy()
    }
}
