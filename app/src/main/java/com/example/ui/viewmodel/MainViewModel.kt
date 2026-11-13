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
import com.example.service.ai.ParsedTask
import com.example.service.alarm.AlarmScheduler
import com.example.service.speech.SpeechManager
import com.example.service.speech.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    private val db = AppDatabase.getDatabase(application)
    private val repository = ReminderRepository(db.reminderDao())
    private val speechManager = SpeechManager(application)
    private val aiParser = GeminiSemanticParser()
    private val themePreferences = com.example.ui.theme.ThemePreferences(application)
    private val ttsSpeaker = com.example.service.speech.TtsSpeaker(application)

    // Conversational Agent & Tool Registry
    private val toolRegistry = com.example.service.conversation.AssistantToolRegistry(application, repository)
    private val conversationalEngine = com.example.service.conversation.ConversationalAiEngine(toolRegistry)

    private val _conversationState = MutableStateFlow(com.example.service.conversation.ConversationState())
    val conversationState: StateFlow<com.example.service.conversation.ConversationState> = _conversationState.asStateFlow()

    private val initialWelcomeMessage = com.example.service.conversation.ChatMessage(
        sender = com.example.service.conversation.MessageSender.ASSISTANT,
        text = "سلام! من دستیار مدیریت یادآورها و عادت‌های شما هستم. می‌توانید با زبان طبیعی و به‌صورت صوتی یا متنی، یادآور بسازید، زمان یا عنوانش را تغییر دهید، آن را جابه‌جا یا حذف کنید، برنامه‌های تکرارشونده بسازید و تنظیمات یادت را کنترل کنید. اگر اطلاعاتی مبهم یا ناقص باشد، قبل از اجرا از شما سؤال می‌پرسم.",
        suggestedReplies = listOf(
            "فردا ساعت ۱۰ یادم بنداز برم بانک",
            "هر روز به‌جز جمعه ساعت ۷ ورزش رو یادم بنداز",
            "یادآورهای فردا رو نشون بده",
            "پیش‌اعلان رو روی ۱۵ دقیقه بذار"
        )
    )

    private val _chatMessages = MutableStateFlow<List<com.example.service.conversation.ChatMessage>>(listOf(initialWelcomeMessage))
    val chatMessages: StateFlow<List<com.example.service.conversation.ChatMessage>> = _chatMessages.asStateFlow()

    val themeMode: StateFlow<com.example.ui.theme.ThemeMode> = themePreferences.themeMode

    init {
        viewModelScope.launch {
            speechManager.voiceState.collect { state ->
                if (state is VoiceState.Success && state.recognizedText.isNotBlank()) {
                    val recognized = state.recognizedText
                    speechManager.resetState()
                    // The assistant tab has one conversation pipeline for both typed and spoken input.
                    // Never route assistant-tab speech through the legacy one-shot command parser,
                    // otherwise the recognized words bypass the visible chat history and lose context.
                    if (_uiState.value.currentTab == NavTab.ASSISTANT || _uiState.value.isVoiceSheetOpen) {
                        sendChatMessage(recognized)
                    } else if (_uiState.value.activeClarification != null) {
                        provideClarification(recognized)
                    } else {
                        // Preserve the existing quick-capture flow outside the assistant conversation.
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
        if (clean.isBlank()) return

        val userMsg = com.example.service.conversation.ChatMessage(
            sender = com.example.service.conversation.MessageSender.USER,
            text = clean
        )
        val updatedHistory = _chatMessages.value + userMsg
        _chatMessages.value = updatedHistory

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiParsing = true)
            val response = conversationalEngine.processTurn(
                userInput = clean,
                currentState = _conversationState.value,
                recentHistory = updatedHistory
            )

            _conversationState.value = response.newState

            val assistantMsg = com.example.service.conversation.ChatMessage(
                sender = com.example.service.conversation.MessageSender.ASSISTANT,
                text = response.replyText,
                actionCard = response.actionCard,
                suggestedReplies = response.suggestedReplies
            )

            _chatMessages.value = _chatMessages.value + assistantMsg
            _uiState.value = _uiState.value.copy(isAiParsing = false)

            // Speak response via TTS
            ttsSpeaker.speak(response.replyText)

            if (response.executedResult != null && response.executedResult.success) {
                _uiState.value = _uiState.value.copy(
                    infoMessage = response.executedResult.message
                )
            }
        }
    }

    fun clearConversation() {
        _conversationState.value = com.example.service.conversation.ConversationState()
        _chatMessages.value = listOf(initialWelcomeMessage)
    }

    fun confirmActionCard(card: com.example.service.conversation.ConversationActionCard) {
        viewModelScope.launch {
            when (card.type) {
                com.example.service.conversation.ActionCardType.PREVIEW_NEW_REMINDER -> {
                    card.task?.let { task ->
                        val result = toolRegistry.createReminder(task)
                        val confirmReply = if (result.success) {
                            "یادآور «${task.title}» برای ${task.jalaliDateText} ساعت ${task.timeText} با موفقیت تنظیم شد."
                        } else {
                            "خطا در ایجاد یادآور: ${result.message}"
                        }
                        val entity = result.data as? ReminderEntity
                        val resultCard = card.copy(
                            type = com.example.service.conversation.ActionCardType.EXECUTION_RESULT,
                            targetReminder = entity,
                            statusMessage = confirmReply
                        )
                        _conversationState.value = _conversationState.value.copy(
                            slotStatus = com.example.service.conversation.SlotFillingStatus.COMPLETED,
                            awaitingField = com.example.service.conversation.AwaitingField.NONE,
                            draftTask = null,
                            lastMentionedReminderId = entity?.id
                        )
                        val assistantMsg = com.example.service.conversation.ChatMessage(
                            sender = com.example.service.conversation.MessageSender.ASSISTANT,
                            text = confirmReply,
                            actionCard = resultCard,
                            suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                        )
                        _chatMessages.value = _chatMessages.value + assistantMsg
                        ttsSpeaker.speak(confirmReply)
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
        _conversationState.value = _conversationState.value.copy(
            slotStatus = com.example.service.conversation.SlotFillingStatus.CANCELLED,
            awaitingField = com.example.service.conversation.AwaitingField.NONE,
            draftTask = null
        )
        val cancelMsg = com.example.service.conversation.ChatMessage(
            sender = com.example.service.conversation.MessageSender.ASSISTANT,
            text = "عملیات لغو شد. کار دیگری هست که بتونم براتون انجام بدم؟",
            suggestedReplies = listOf("یادآور جدید", "یادآورهای امروز")
        )
        _chatMessages.value = _chatMessages.value + cancelMsg
    }

    fun parseSpokenCommand(text: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiParsing = true, activeClarification = null)
            val parsedList = aiParser.parseCommand(text)

            // Check if clarification is needed on any extracted task
            val taskNeedingClarification = parsedList.firstOrNull { it.needsClarification }
            if (taskNeedingClarification != null) {
                val clarificationType = if (!taskNeedingClarification.hasExplicitTime && !taskNeedingClarification.hasExplicitDate) {
                    ClarificationType.MISSING_TIME_OR_DATE
                } else {
                    ClarificationType.MISSING_TITLE
                }

                val question = if (clarificationType == ClarificationType.MISSING_TIME_OR_DATE) {
                    "ساعت یا تاریخ برای یادآوری «${taskNeedingClarification.title}» مشخص نشده است؛ مایلید چه زمانی یادآوری شود؟"
                } else {
                    "زمان یادآوری روی ${taskNeedingClarification.jalaliDateText} ساعت ${taskNeedingClarification.timeText} مشخص شد. لطفاً عنوان یا موضوع این یادآوری را بفرمایید:"
                }

                val suggestions = if (clarificationType == ClarificationType.MISSING_TIME_OR_DATE) {
                    listOf("نیم ساعت دیگر", "۱ ساعت دیگر", "امروز ساعت ۱۸:۰۰", "فردا ۹ صبح", "پس‌فردا ساعت ۱۵:۰۰")
                } else {
                    listOf("جلسه کاری", "تماس تلفنی", "خرید", "مصرف دارو")
                }

                val prompt = ClarificationPrompt(
                    type = clarificationType,
                    question = question,
                    tentativeTask = taskNeedingClarification,
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
                val newTimestamp = if (extracted.hasExplicitTime || extracted.hasExplicitDate) {
                    extracted.triggerTimestamp
                } else {
                    System.currentTimeMillis() + 3600 * 1000L
                }
                val j = PersianDateEngine.timestampToJalali(newTimestamp)
                val timeStr = PersianDateEngine.formatPersianTime(j.hour, j.minute)
                val dateStr = j.formatPersianNumeric()

                tentative.copy(
                    triggerTimestamp = newTimestamp,
                    jalaliDateText = dateStr,
                    timeText = timeStr,
                    hasExplicitTime = true,
                    hasExplicitDate = true,
                    clarificationQuestion = null,
                    isAmbiguous = false
                )
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
        val current = _uiState.value.parsedTasksPreview.toMutableList()
        current.add(0, prompt.tentativeTask)
        _uiState.value = _uiState.value.copy(
            activeClarification = null,
            parsedTasksPreview = current
        )
    }

    fun confirmPreviewTask(task: ParsedTask) {
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
            AlarmScheduler.scheduleReminder(context, entity.copy(id = id))

            // Remove confirmed from preview
            val updated = _uiState.value.parsedTasksPreview.filter { it != task }
            _uiState.value = _uiState.value.copy(
                parsedTasksPreview = updated,
                infoMessage = "یادآوری «${entity.title}» با موفقیت ثبت شد."
            )
            if (updated.isEmpty()) {
                _uiState.value = _uiState.value.copy(isVoiceSheetOpen = false)
            }
        }
    }

    fun confirmAllPreviewTasks() {
        viewModelScope.launch {
            val context = getApplication<Application>()
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
                    advanceAlertMinutes = task.advanceAlertMinutes
                )
                val id = repository.insert(entity)
                AlarmScheduler.scheduleReminder(context, entity.copy(id = id))
            }
            _uiState.value = _uiState.value.copy(
                parsedTasksPreview = emptyList(),
                isVoiceSheetOpen = false,
                infoMessage = "تمام یادآوری‌ها با موفقیت ثبت شدند."
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
                AlarmScheduler.scheduleReminder(context, reminder.copy(id = id))
                _uiState.value = _uiState.value.copy(infoMessage = "یادآوری جدید ثبت شد.")
            } else {
                repository.update(reminder)
                AlarmScheduler.cancelReminder(context, reminder.id)
                AlarmScheduler.scheduleReminder(context, reminder)
                _uiState.value = _uiState.value.copy(infoMessage = "تغییرات ذخیره شد.")
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
                AlarmScheduler.scheduleReminder(context, reminder.copy(isCompleted = false))
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
            AlarmScheduler.scheduleReminder(context, updated)
            com.example.widget.TodayTasksWidgetReceiver.updateAllWidgets(context)
            _uiState.value = _uiState.value.copy(infoMessage = "یادآوری به ${addedMinutes} دقیقه بعد موکول شد.")
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
