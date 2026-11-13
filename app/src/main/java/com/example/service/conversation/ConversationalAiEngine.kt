package com.example.service.conversation

import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.service.ai.ParsedTask
import com.example.service.ai.PersianCommandAnalyzer
import com.example.service.ai.ValidationStatus
import java.util.Calendar
import java.util.TimeZone

data class ConversationalResponse(
    val replyText: String,
    val newState: ConversationState,
    val actionCard: ConversationActionCard? = null,
    val suggestedReplies: List<String> = emptyList(),
    val executedResult: ToolResult? = null
)

/**
 * Intelligent Multi-Turn Persian Conversational Agent.
 * Handles:
 * 1. General conversation, explanations, advice, learning, and ideation (General AI Chatbot)
 * 2. Multi-turn slot-filling and natural dialogue
 * 3. Disambiguation when multiple matching reminders exist (asking user to select exact one)
 * 4. Semantic distinction between deletion vs alarm cancellation
 * 5. Relative offsets, corrections, anaphora/references, and 10 executive tools
 */
class ConversationalAiEngine(
    private val toolRegistry: AssistantToolRegistry? = null,
    private val defaultTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Tehran")
) {

    private val generalAiService = GeneralConversationalAiService()

    /**
     * Processes a single turn of user conversation given the current state and recent history.
     */
    suspend fun processTurn(
        userInput: String,
        currentState: ConversationState,
        recentHistory: List<ChatMessage> = emptyList(),
        referenceTimeMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = defaultTimeZone
    ): ConversationalResponse {
        val cleanInput = userInput.trim()
        if (cleanInput.isBlank()) {
            return ConversationalResponse(
                replyText = "لطفاً فرمان یا سؤالتان را بفرمایید.",
                newState = currentState
            )
        }

        // 1. Check if user is responding to an active pending question
        if (currentState.isWaitingForAnswer()) {
            val pendingResolution = handlePendingAnswer(cleanInput, currentState, referenceTimeMillis, timeZone)
            if (pendingResolution != null) {
                return pendingResolution
            }
        }

        // 2. Check for explicit corrections to previous statements ("نه، منظورم فردا نبود، پسفردا بود")
        if (isCorrection(cleanInput) && currentState.draftTask != null) {
            return handleCorrection(cleanInput, currentState, referenceTimeMillis, timeZone)
        }

        // 3. Greetings and casual conversation are routed to the conversational model
        // below, so replies can use conversation history instead of repeating a fixed template.

        // 4. Check for consultation & reasoning questions ("به نظرت ورزش ساعت ۱۲ شب خوبه؟", "من فردا ساعت ۸ بیدار میشم به نظرت چه ساعتی ورزش کنم؟", "میخوام هر روز ۴ ساعت بخوابم")
        if (isConsultationOrAdviceQuestion(cleanInput)) {
            val advisoryReply = generalAiService.generateChatReply(
                userMessage = cleanInput,
                recentHistory = recentHistory,
                userContextSummary = toolRegistry?.getUserMemoryPreferences()?.message
            )
            return ConversationalResponse(
                replyText = advisoryReply,
                newState = currentState.copy(
                    activeIntent = ConversationIntent.CONSULTATION_OR_ADVICE,
                    awaitingField = AwaitingField.NONE,
                    slotStatus = SlotFillingStatus.IDLE
                ),
                suggestedReplies = listOf("ساعت ۸ شب تنظیم کن", "یک ساعت مناسب پیشنهاد بده", "ممنون از راهنماییت")
            )
        }

        // 5. Check for user routine / habit declarations ("من معمولاً ساعت ۸ صبح بیدار میشم")
        val routineResponse = handleUserRoutineStatement(cleanInput, currentState)
        if (routineResponse != null) {
            return routineResponse
        }

        // 6. Check for application setting commands ("از این به بعد ۱۵ دقیقه قبل هم یادآوری کن", "تم رو تاریک کن")
        val appSettingResponse = handleAppSettingCommand(cleanInput, currentState)
        if (appSettingResponse != null) {
            return appSettingResponse
        }

        // 7. Check for references to previous reminders ("اون رو نیم ساعت عقب بنداز", "قرار فردام رو دو ساعت عقب بنداز", "اون قراری که دیروز تنظیم کردیم رو برای هفته بعد ببر", "قرارم رو حذف کن")
        val referenceAction = handleReferenceToPast(cleanInput, currentState, referenceTimeMillis, timeZone)
        if (referenceAction != null) {
            return referenceAction
        }

        // 8. Check for questions about existing reminders ("چه یادآورهایی دارم؟", "قرارهای این هفته")
        if (isQueryRemindersIntent(cleanInput)) {
            return handleQueryReminders(cleanInput, currentState, referenceTimeMillis, timeZone)
        }

        // 9. Check if input is a Reminder / Appointment Action
        if (isAppointmentOrReminderAction(cleanInput)) {
            return handleAppointmentOrReminderAction(cleanInput, currentState, referenceTimeMillis, timeZone)
        }

        // 10. General AI Conversational Assistant (Gemini)
        // Any everyday talk, thoughts, emotions, health questions, or general queries go to GeneralConversationalAiService
        val isNonsenseOrGibberish = cleanInput.contains("الکی") || cleanInput.contains("فلان بیسار") ||
                cleanInput.contains("بی‌معنی") || cleanInput.contains("چرت و پرت")
        if (!isNonsenseOrGibberish && cleanInput.isNotBlank()) {
            val generalReply = generalAiService.generateChatReply(
                userMessage = cleanInput,
                recentHistory = recentHistory,
                userContextSummary = toolRegistry?.getUserMemoryPreferences()?.message
            )
            val isGreeting = checkChitChatOrGreeting(cleanInput) != null
            return ConversationalResponse(
                replyText = generalReply,
                newState = currentState.copy(
                    activeIntent = if (isGreeting) ConversationIntent.GREETING_OR_CHITCHAT else ConversationIntent.INFORMATIONAL_QA,
                    awaitingField = AwaitingField.NONE,
                    slotStatus = SlotFillingStatus.IDLE
                ),
                suggestedReplies = listOf("فردا ساعت ۱۰ یادم بنداز", "یادآورهای امروز من چیه؟", "بیشتر برام بگو")
            )
        }

        // 11. Unrecognized or blank
        return ConversationalResponse(
            replyText = "متوجه درخواست شما نشدم؛ لطفاً واضح‌تر بفرمایید، یا بگویید چه کاری و در چه زمانی را مایلید یادتان بیندازم.",
            newState = currentState.copy(
                activeIntent = ConversationIntent.UNKNOWN,
                awaitingField = AwaitingField.NONE,
                slotStatus = SlotFillingStatus.IDLE
            ),
            suggestedReplies = listOf("فردا ۱۰ صبح یادم بنداز برم بانک", "یادآورهای امروز", "هوش مصنوعی چطور کار می‌کنه؟")
        )
    }

    /**
     * Handles answering an active clarification question
     */
    private suspend fun handlePendingAnswer(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse? {
        val draft = state.draftTask

        when (state.awaitingField) {
            AwaitingField.EXECUTION_CONFIRMATION -> {
                if (isCorrection(input) && draft != null) {
                    return handleCorrection(input, state, referenceTimeMillis, timeZone)
                } else if (isPositiveConfirmation(input)) {
                    // Confirmed: Execute creation or update
                    return executeConfirmedDraft(draft, state)
                } else if (isNegativeRejection(input)) {
                    // Rejected: Cancel and reset
                    return ConversationalResponse(
                        replyText = "تنظیم یادآور لغو شد. دستور یا یادآوری دیگری دارید؟",
                        newState = state.copy(
                            slotStatus = SlotFillingStatus.CANCELLED,
                            awaitingField = AwaitingField.NONE,
                            draftTask = null,
                            lastAssistantQuestion = null
                        ),
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورهای من")
                    )
                }
            }

            AwaitingField.AMBIGUOUS_HOUR_AM_PM -> {
                if (draft != null) {
                    val resolved = resolveHourAmbiguity(input, draft, timeZone)
                    if (resolved != null) {
                        return proceedToConfirmationOrCompletion(resolved, state, referenceTimeMillis)
                    }
                }
            }

            AwaitingField.TITLE -> {
                if (draft != null) {
                    val cleanTitle = extractCleanTitleFromReply(input)
                    val updated = draft.copy(
                        title = cleanTitle,
                        hasExplicitTitle = true,
                        clarificationQuestion = null
                    )
                    return proceedToConfirmationOrCompletion(updated, state, referenceTimeMillis)
                }
            }

            AwaitingField.TIME_OR_DATE -> {
                if (draft != null) {
                    val timeAnalyzed = PersianCommandAnalyzer.analyze(input, referenceTimeMillis, timeZone)
                    if (timeAnalyzed.validationStatus == ValidationStatus.CONFLICT) {
                        val q = timeAnalyzed.clarificationQuestion ?: "تاریخ‌های گفته‌شده با هم سازگار نیستند؛ لطفاً تاریخ را روشن کنید."
                        return ConversationalResponse(
                            replyText = q,
                            newState = state.copy(lastAssistantQuestion = q),
                            suggestedReplies = timeAnalyzed.quickSuggestions
                        )
                    }
                    val isAmbiguous = timeAnalyzed.validationStatus == ValidationStatus.AMBIGUOUS
                    val dateProvided = timeAnalyzed.hasExplicitDate
                    val timeProvided = timeAnalyzed.hasExplicitTime
                    val updated = if (dateProvided || timeProvided) {
                        val mergedCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = draft.triggerTimestamp }
                        if (dateProvided) {
                            val dateTs = timeAnalyzed.targetTimestamp ?: placeholderTimestampForDate(
                                timeAnalyzed.jalaliDateText, referenceTimeMillis, timeZone
                            )
                            val dateCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = dateTs }
                            mergedCalendar.set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR))
                            mergedCalendar.set(Calendar.MONTH, dateCalendar.get(Calendar.MONTH))
                            mergedCalendar.set(Calendar.DAY_OF_MONTH, dateCalendar.get(Calendar.DAY_OF_MONTH))
                        }
                        if (timeProvided) {
                            val answerHour = timeAnalyzed.hour ?: mergedCalendar.get(Calendar.HOUR_OF_DAY)
                            val answerMinute = timeAnalyzed.minute ?: 0
                            mergedCalendar.set(Calendar.HOUR_OF_DAY, answerHour)
                            mergedCalendar.set(Calendar.MINUTE, answerMinute)
                            mergedCalendar.set(Calendar.SECOND, 0)
                            mergedCalendar.set(Calendar.MILLISECOND, 0)
                        }
                        val mergedTimestamp = mergedCalendar.timeInMillis
                        val mergedJalali = PersianDateEngine.timestampToJalali(mergedTimestamp, timeZone)
                        draft.copy(
                            triggerTimestamp = mergedTimestamp,
                            jalaliDateText = mergedJalali.formatPersianNumeric(),
                            timeText = PersianDateEngine.formatPersianTime(mergedJalali.hour, mergedJalali.minute),
                            hasExplicitDate = draft.hasExplicitDate || dateProvided,
                            hasExplicitTime = if (isAmbiguous) false else (draft.hasExplicitTime || timeProvided),
                            isAmbiguous = isAmbiguous,
                            clarificationQuestion = timeAnalyzed.clarificationQuestion
                        )
                    } else null

                    if (updated != null) {
                        if (updated.isAmbiguous) {
                            val q = updated.clarificationQuestion ?: "منظورتان صبح است یا عصر؟"
                            return ConversationalResponse(
                                replyText = q,
                                newState = state.copy(
                                    draftTask = updated,
                                    awaitingField = AwaitingField.AMBIGUOUS_HOUR_AM_PM,
                                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                                    lastAssistantQuestion = q
                                ),
                                suggestedReplies = listOf("صبح", "عصر")
                            )
                        }
                        return proceedToConfirmationOrCompletion(updated, state, referenceTimeMillis)
                    } else {
                        val q = timeAnalyzed.clarificationQuestion ?: "هنوز تاریخ یا ساعت مشخص نشده است؛ لطفاً یک تاریخ یا ساعت دقیق بفرمایید."
                        return ConversationalResponse(
                            replyText = q,
                            newState = state.copy(
                                activeIntent = ConversationIntent.CREATE_REMINDER,
                                awaitingField = AwaitingField.TIME_OR_DATE,
                                slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                                lastAssistantQuestion = q
                            ),
                            suggestedReplies = timeAnalyzed.quickSuggestions
                        )
                    }
                }
            }

            AwaitingField.RESCHEDULE_OFFSET -> {
                val offset = extractMinuteOffset(input)
                val direction = state.pendingRescheduleDirection ?: 1
                if (offset == null) {
                    val question = if (direction > 0) {
                        "چند دقیقه یا چند ساعت عقب بیندازم؟"
                    } else {
                        "چند دقیقه یا چند ساعت زودتر تنظیم کنم؟"
                    }
                    return ConversationalResponse(
                        replyText = question,
                        newState = state.copy(lastAssistantQuestion = question),
                        suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                    )
                }

                val targetId = state.targetReminderId ?: state.lastMentionedReminderId
                if (targetId == null) {
                    val question = "کدام یادآور را تغییر بدهم؟"
                    return ConversationalResponse(
                        replyText = question,
                        newState = state.copy(awaitingField = AwaitingField.NONE, pendingRescheduleDirection = null),
                        suggestedReplies = listOf("مشاهده یادآورها")
                    )
                }
                if (toolRegistry == null) {
                    val message = "سرویس یادآورها در دسترس نیست؛ تغییر زمان انجام نشد و هیچ تغییری اعمال نشد."
                    return ConversationalResponse(
                        replyText = message,
                        newState = state.copy(awaitingField = AwaitingField.NONE, pendingRescheduleDirection = null),
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                }

                val result = toolRegistry.rescheduleReminder(targetId, offsetMinutes = offset * direction)
                return ConversationalResponse(
                    replyText = if (result.success) result.message else "تغییر زمان انجام نشد: ${result.message}",
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        awaitingField = AwaitingField.NONE,
                        pendingRescheduleDirection = null,
                        targetReminderId = targetId,
                        lastMentionedReminderId = targetId,
                        slotStatus = if (result.success) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                    ),
                    executedResult = result,
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            }

            AwaitingField.WHICH_REMINDER_TO_SELECT -> {
                val selectedId = resolveCandidateSelection(input, state.candidateReminderIds)
                if (selectedId != null) {
                    if (toolRegistry != null) {
                        val remRes = toolRegistry.getReminderDetails(selectedId)
                        val reminder = remRes.data as? ReminderEntity
                        if (reminder != null) {
                            // Check what user wanted to do with this reminder (delete or reschedule)
                            if (state.activeIntent == ConversationIntent.DELETE_REMINDER) {
                                val delRes = toolRegistry.deleteOrCancelReminder(reminder.id, isHardDelete = true)
                                return ConversationalResponse(
                                    replyText = delRes.message,
                                    newState = state.copy(
                                        targetReminderId = null,
                                        lastMentionedReminderId = null,
                                        awaitingField = AwaitingField.NONE,
                                        candidateReminderIds = emptyList()
                                    ),
                                    executedResult = delRes,
                                    suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                                )
                            } else if (state.pendingRescheduleDirection != null) {
                                val question = if (state.pendingRescheduleDirection > 0) {
                                    "قرار «${reminder.title}» انتخاب شد. چند دقیقه یا ساعت عقب بیندازم؟"
                                } else {
                                    "قرار «${reminder.title}» انتخاب شد. چند دقیقه یا ساعت زودتر تنظیم کنم؟"
                                }
                                return ConversationalResponse(
                                    replyText = question,
                                    newState = state.copy(
                                        targetReminderId = selectedId,
                                        lastMentionedReminderId = selectedId,
                                        awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                                        candidateReminderIds = emptyList(),
                                        lastAssistantQuestion = question
                                    ),
                                    suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                                )
                            } else {
                                return ConversationalResponse(
                                    replyText = "قرار «${reminder.title}» (${reminder.jalaliDateText} ساعت ${reminder.timeText}) انتخاب شد. چه تغییری مایلید اعمال شود؟",
                                    newState = state.copy(
                                        targetReminderId = selectedId,
                                        lastMentionedReminderId = selectedId,
                                        awaitingField = AwaitingField.NONE,
                                        candidateReminderIds = emptyList()
                                    ),
                                    suggestedReplies = listOf("نیم ساعت عقب بنداز", "حذفش کن", "ساعتش رو عوض کن")
                                )
                            }
                        }
                    }
                    return ConversationalResponse(
                        replyText = "یادآور مورد نظر با شناسه $selectedId انتخاب شد.",
                        newState = state.copy(
                            targetReminderId = selectedId,
                            lastMentionedReminderId = selectedId,
                            awaitingField = AwaitingField.NONE,
                            candidateReminderIds = emptyList()
                        ),
                        suggestedReplies = listOf("نیم ساعت عقب بنداز", "حذفش کن", "ساعتش رو عوض کن")
                    )
                }
            }

            AwaitingField.NONE -> {
                // No active field pending
            }
        }
        return null
    }

    /**
     * Resolves ambiguity when user clarifies morning or evening ("صبح", "عصر", "غروب", "شب", "۱۰ صبح")
     */
    private fun resolveHourAmbiguity(input: String, draft: ParsedTask, timeZone: TimeZone): ParsedTask? {
        val lower = input.trim()
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = draft.triggerTimestamp }
        var curHour = cal.get(Calendar.HOUR_OF_DAY)
        val curMinute = cal.get(Calendar.MINUTE)

        if (lower.contains("صبح") || lower.contains("بامداد") || lower.contains("اول وقت")) {
            if (curHour >= 12) curHour -= 12
        } else if (lower.contains("عصر") || lower.contains("غروب") || lower.contains("شب") || lower.contains("بعدازظهر") || lower.contains("بعد از ظهر")) {
            if (curHour < 12) curHour += 12
        } else {
            val num = extractNumber(lower)
            if (num != null) {
                curHour = num
                if ((lower.contains("عصر") || lower.contains("شب")) && curHour < 12) curHour += 12
            } else {
                return null
            }
        }

        cal.set(Calendar.HOUR_OF_DAY, curHour)
        cal.set(Calendar.MINUTE, curMinute)
        val newTs = cal.timeInMillis
        val j = PersianDateEngine.timestampToJalali(newTs, timeZone)
        val timeStr = PersianDateEngine.formatPersianTime(j.hour, j.minute)

        return draft.copy(
            triggerTimestamp = newTs,
            jalaliDateText = j.formatPersianNumeric(),
            timeText = timeStr,
            isAmbiguous = false,
            clarificationQuestion = null,
            hasExplicitTime = true
        )
    }

    private fun placeholderTimestampForDate(
        jalaliDateText: String?,
        referenceTimeMillis: Long,
        timeZone: TimeZone,
        hour: Int? = null,
        minute: Int? = null
    ): Long {
        val current = PersianDateEngine.timestampToJalali(referenceTimeMillis, timeZone)
        val parts = jalaliDateText?.let { PersianDateEngine.toEnglishDigits(it).split("/") }
        val year = parts?.getOrNull(0)?.toIntOrNull() ?: current.year
        val month = parts?.getOrNull(1)?.toIntOrNull() ?: current.month
        val day = parts?.getOrNull(2)?.toIntOrNull() ?: current.day
        return PersianDateEngine.jalaliToTimestamp(
            year, month, day, hour ?: current.hour, minute ?: current.minute, timeZone
        )
    }

    /**
     * Proceeds to confirmation or asks remaining slot if incomplete
     */
    private fun proceedToConfirmationOrCompletion(task: ParsedTask, state: ConversationState, referenceTimeMillis: Long): ConversationalResponse {
        if (task.isAmbiguous || task.hasAmbiguousTimePeriod) {
            val hour = task.timeText.substringBefore(":")
            val q = "منظورتان ساعت $hour صبح است یا شب؟"
            return ConversationalResponse(
                replyText = task.clarificationQuestion ?: q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    draftTask = task,
                    awaitingField = AwaitingField.AMBIGUOUS_HOUR_AM_PM,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    lastAssistantQuestion = task.clarificationQuestion ?: q
                ),
                suggestedReplies = listOf("ساعت $hour صبح", "ساعت $hour شب")
            )
        }

        if (!task.hasExplicitTitle || isGenericSubject(task.title)) {
            val q = "برای ${task.jalaliDateText} ساعت ${task.timeText}، چه کاری رو باید یادتون بندازم؟"
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    draftTask = task,
                    awaitingField = AwaitingField.TITLE,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = listOf("جلسه کاری", "تماس تلفنی", "رفتن به بانک", "خرید نان")
            )
        }

        // Never ask for confirmation until both date and time are explicitly resolved.
        if (!task.hasExplicitTime || !task.hasExplicitDate) {
            val missingDate = !task.hasExplicitDate
            val missingTime = !task.hasExplicitTime
            val q = when {
                missingDate && missingTime -> "چه روز و ساعتی یادآوری «${task.title}» رو ثبت کنم؟"
                missingDate -> "ساعت مشخص است؛ تاریخ یادآوری «${task.title}» چه روزی باشد؟"
                else -> "تاریخ مشخص است؛ یادآوری «${task.title}» را چه ساعتی تنظیم کنم؟"
            }
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    draftTask = task,
                    awaitingField = AwaitingField.TIME_OR_DATE,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = when {
                    missingDate && missingTime -> listOf("نیم ساعت دیگه", "فردا ساعت ۹ صبح", "امروز ساعت ۱۸:۰۰")
                    missingDate -> listOf("امروز", "فردا", "پس‌فردا", "سه روز دیگر")
                    else -> listOf("ساعت ۹ صبح", "ساعت ۱۸:۰۰", "نیم ساعت دیگر")
                }
            )
        }

        if (task.triggerTimestamp <= referenceTimeMillis) {
            val q = "زمان انتخاب‌شده در گذشته است؛ لطفاً تاریخ و ساعت آینده را مشخص کنید."
            val incomplete = task.copy(hasExplicitDate = false, hasExplicitTime = false, isAmbiguous = false)
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    draftTask = incomplete,
                    awaitingField = AwaitingField.TIME_OR_DATE,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = listOf("فردا ساعت ۹ صبح", "پس‌فردا ساعت ۱۰ صبح", "نیم ساعت دیگر")
            )
        }

        val confirmMsg = "یادآور «${task.title}» برای ${task.jalaliDateText} ساعت ${task.timeText} تنظیم بشه؟"
        val card = ConversationActionCard(
            type = ActionCardType.PREVIEW_NEW_REMINDER,
            task = task,
            primaryActionLabel = "آره، ثبت کن",
            secondaryActionLabel = "لغو"
        )
        return ConversationalResponse(
            replyText = confirmMsg,
            newState = state.copy(
                activeIntent = ConversationIntent.CREATE_REMINDER,
                draftTask = task,
                awaitingField = AwaitingField.EXECUTION_CONFIRMATION,
                slotStatus = SlotFillingStatus.AWAITING_CONFIRMATION,
                lastAssistantQuestion = confirmMsg
            ),
            actionCard = card,
            suggestedReplies = listOf("آره", "نه، لغوش کن", "تغییر ساعت")
        )
    }

    /**
     * Executes draft task after user confirmed
     */
    private suspend fun executeConfirmedDraft(task: ParsedTask?, state: ConversationState): ConversationalResponse {
        if (task == null) {
            return ConversationalResponse(
                replyText = "اطلاعات یادآوری در دسترس نیست. لطفاً مجدداً بفرمایید.",
                newState = state.copy(awaitingField = AwaitingField.NONE, slotStatus = SlotFillingStatus.IDLE)
            )
        }

        val result = toolRegistry?.createReminder(task) ?: ToolResult(
            success = false,
            message = "سرویس ثبت یادآور در دسترس نیست؛ هیچ یادآوری ذخیره نشد.",
            errorCode = "TOOL_REGISTRY_UNAVAILABLE"
        )

        val entity = result.data as? ReminderEntity

        return ConversationalResponse(
            replyText = if (result.success) {
                result.message
            } else {
                "متأسفانه در ثبت یادآور خطایی رخ داد: ${result.message}"
            },
            newState = state.copy(
                slotStatus = if (result.success) SlotFillingStatus.COMPLETED else SlotFillingStatus.AWAITING_CONFIRMATION,
                awaitingField = if (result.success) AwaitingField.NONE else AwaitingField.EXECUTION_CONFIRMATION,
                draftTask = if (result.success) null else task,
                targetReminderId = if (result.success) entity?.id else state.targetReminderId,
                lastMentionedReminderId = if (result.success) entity?.id else state.lastMentionedReminderId,
                lastAssistantQuestion = if (result.success) null else "ثبت یادآور «${task.title}» را دوباره امتحان کنم؟"
            ),
            actionCard = ConversationActionCard(
                type = if (result.success) ActionCardType.EXECUTION_RESULT else ActionCardType.PREVIEW_NEW_REMINDER,
                task = task,
                targetReminder = entity,
                primaryActionLabel = if (result.success) "انجام شد" else "تلاش دوباره",
                secondaryActionLabel = "لغو",
                statusMessage = if (result.success) result.message else "ثبت انجام نشد؛ اطلاعات یادآور حفظ شده است. ${result.message}"
            ),
            suggestedReplies = if (result.success) listOf("یادآور جدید", "یادآورهای امروز", "ممنون") else listOf("آره، دوباره ثبت کن", "لغو", "تغییر زمان"),
            executedResult = result
        )
    }

    /**
     * Handles corrections like "نه، منظورم فردا نبود، پسفردا بود"
     */
    private fun handleCorrection(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse {
        val draft = state.draftTask ?: return ConversationalResponse(
            replyText = "چیزی برای اصلاح یافت نشد؛ چه یادآوری مد نظرتان است؟",
            newState = state.copy(awaitingField = AwaitingField.NONE)
        )

        val clean = input.replace("نه،", "").replace("نه", "").replace("منظورم", "").trim()
        val reAnalyzed = PersianCommandAnalyzer.analyze(clean, referenceTimeMillis, timeZone)
        if (reAnalyzed.validationStatus == ValidationStatus.CONFLICT) {
            val q = reAnalyzed.clarificationQuestion ?: "تاریخ‌های گفته‌شده با هم سازگار نیستند؛ لطفاً تاریخ را روشن کنید."
            return ConversationalResponse(replyText = q, newState = state.copy(lastAssistantQuestion = q), suggestedReplies = reAnalyzed.quickSuggestions)
        }
        val isAmbiguous = reAnalyzed.validationStatus == ValidationStatus.AMBIGUOUS
        val updatedTask = if (reAnalyzed.hasExplicitDate || reAnalyzed.hasExplicitTime) {
            val merged = Calendar.getInstance(timeZone).apply { timeInMillis = draft.triggerTimestamp }
            if (reAnalyzed.hasExplicitDate) {
                val dateTs = reAnalyzed.targetTimestamp ?: placeholderTimestampForDate(reAnalyzed.jalaliDateText, referenceTimeMillis, timeZone)
                val dateCal = Calendar.getInstance(timeZone).apply { timeInMillis = dateTs }
                merged.set(Calendar.YEAR, dateCal.get(Calendar.YEAR))
                merged.set(Calendar.MONTH, dateCal.get(Calendar.MONTH))
                merged.set(Calendar.DAY_OF_MONTH, dateCal.get(Calendar.DAY_OF_MONTH))
            }
            if (reAnalyzed.hasExplicitTime) {
                merged.set(Calendar.HOUR_OF_DAY, reAnalyzed.hour ?: merged.get(Calendar.HOUR_OF_DAY))
                merged.set(Calendar.MINUTE, reAnalyzed.minute ?: 0)
                merged.set(Calendar.SECOND, 0)
                merged.set(Calendar.MILLISECOND, 0)
            }
            val ts = merged.timeInMillis
            val j = PersianDateEngine.timestampToJalali(ts, timeZone)
            draft.copy(
                triggerTimestamp = ts,
                jalaliDateText = j.formatPersianNumeric(),
                timeText = PersianDateEngine.formatPersianTime(j.hour, j.minute),
                hasExplicitDate = draft.hasExplicitDate || reAnalyzed.hasExplicitDate,
                hasExplicitTime = if (isAmbiguous) false else (draft.hasExplicitTime || reAnalyzed.hasExplicitTime),
                isAmbiguous = isAmbiguous,
                clarificationQuestion = reAnalyzed.clarificationQuestion
            )
        } else draft

        return proceedToConfirmationOrCompletion(updatedTask, state, referenceTimeMillis)
    }

    /**
     * Handles references to previous reminders ("اون رو نیم ساعت عقب بنداز", "یادآور قبلی رو پاک کن")
     */
    private suspend fun handleReferenceToPast(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse? {
        val lower = input.lowercase()

        // 1. Rescheduling reference for Tomorrow ("قرار فردام رو دو ساعت عقب بنداز")
        if ((lower.contains("فردام") || lower.contains("فردا")) && (lower.contains("عقب بنداز") || lower.contains("به تعویق") || lower.contains("تاخیر") || lower.contains("دیرتر"))) {
            val offsetMinutes = extractMinuteOffset(lower)
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = referenceTimeMillis
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTomorrow = cal.timeInMillis
            val endTomorrow = startTomorrow + 86400000L - 1

            if (toolRegistry != null) {
                val remRes = toolRegistry.getRemindersInRange(startTomorrow, endTomorrow)
                val list = (remRes.data as? List<*>)?.filterIsInstance<ReminderEntity>() ?: emptyList()
                if (list.size == 1) {
                    val r = list.first()
                    if (offsetMinutes == null) {
                        val question = "قرار فردا «${r.title}» پیدا شد. چند دقیقه یا ساعت عقب بیندازم؟"
                        return ConversationalResponse(
                            replyText = question,
                            newState = state.copy(
                                activeIntent = ConversationIntent.UPDATE_REMINDER,
                                targetReminderId = r.id,
                                lastMentionedReminderId = r.id,
                                awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                                pendingRescheduleDirection = 1,
                                lastAssistantQuestion = question
                            ),
                            suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                        )
                    }
                    val res = toolRegistry.rescheduleReminder(r.id, offsetMinutes = offsetMinutes)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "تغییر زمان انجام نشد: ${res.message}",
                        newState = state.copy(
                            activeIntent = ConversationIntent.UPDATE_REMINDER,
                            targetReminderId = r.id,
                            lastMentionedReminderId = r.id,
                            awaitingField = AwaitingField.NONE
                        ),
                        executedResult = res,
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                } else if (list.size > 1) {
                    val card = ConversationActionCard(
                        type = ActionCardType.SELECT_FROM_MULTIPLE_REMINDERS,
                        candidateReminders = list,
                        primaryActionLabel = "انتخاب این مورد",
                        secondaryActionLabel = "انصراف"
                    )
                    return ConversationalResponse(
                        replyText = "برای فردا چند یادآور پیدا کردم. کدام مورد را می‌خواهید عقب بیندازید؟",
                        newState = state.copy(
                            activeIntent = ConversationIntent.UPDATE_REMINDER,
                            awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT,
                            candidateReminderIds = list.map { it.id },
                            pendingRescheduleDirection = if (offsetMinutes == null) 1 else null
                        ),
                        actionCard = card,
                        suggestedReplies = list.mapIndexed { idx, _ -> "مورد ${when(idx){0->"اول"; 1->"دوم"; else->"سوم"}}" }
                    )
                }
            }
            return ConversationalResponse(
                replyText = if (toolRegistry == null) {
                    "سرویس یادآورها در دسترس نیست؛ قرار فردا تغییر نکرد و هیچ تغییری انجام نشد."
                } else {
                    "برای فردا یادآوری پیدا نکردم؛ چیزی تغییر نکرده است."
                },
                newState = state.copy(activeIntent = ConversationIntent.UPDATE_REMINDER, awaitingField = AwaitingField.NONE),
                suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
            )
        }

        // 2. Move past reminder to next week ("اون قراری که دیروز تنظیم کردیم رو برای هفته بعد ببر")
        if (lower.contains("هفته بعد") && (lower.contains("ببر") || lower.contains("منتقل") || lower.contains("جابجا") || lower.contains("موکول"))) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            val offsetMins = 7 * 24 * 60 // 7 days in minutes
            if (targetId != null && toolRegistry != null) {
                val res = toolRegistry.rescheduleReminder(targetId, offsetMinutes = offsetMins)
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "تغییر زمان انجام نشد: ${res.message}",
                    newState = state.copy(activeIntent = ConversationIntent.UPDATE_REMINDER, awaitingField = AwaitingField.NONE),
                    executedResult = res,
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            } else {
                return ConversationalResponse(
                    replyText = if (toolRegistry == null && targetId != null) { "سرویس یادآورها در دسترس نیست؛ قرار مشخص‌شده به هفته بعد منتقل نشد و هیچ تغییری انجام نشد." } else { "نتوانستم یادآور مشخصی را برای انتقال پیدا کنم؛ لطفاً بگویید کدام قرار را تغییر بدهم." }, 
                    newState = state.copy(activeIntent = ConversationIntent.UPDATE_REMINDER, awaitingField = AwaitingField.NONE),
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            }
        }

        // 2b. Advance earlier ("نیم ساعت جلو بنداز", "یک ساعت زودترش کن")
        if (lower.contains("جلو بنداز") || lower.contains("جلو بکش") || lower.contains("زودتر کن") || lower.contains("زودترش کن")) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            val requestedOffset = extractMinuteOffset(lower)
            val offsetMinutes = requestedOffset?.let { -it }
            if (targetId != null && offsetMinutes == null) {
                val question = "چند دقیقه یا چند ساعت زودتر تنظیم کنم؟"
                return ConversationalResponse(
                    replyText = question,
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        targetReminderId = targetId,
                        lastMentionedReminderId = targetId,
                        awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                        pendingRescheduleDirection = -1,
                        lastAssistantQuestion = question
                    ),
                    suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                )
            }
            if (targetId != null && toolRegistry != null && offsetMinutes != null) {
                val res = toolRegistry.rescheduleReminder(targetId, offsetMinutes = offsetMinutes)
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        awaitingField = AwaitingField.NONE,
                        slotStatus = if (res.success) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                    ),
                    executedResult = res,
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            } else if (targetId != null) {
                val minsDesc = if (offsetMinutes < 0) "${-offsetMinutes} دقیقه جلو کشیده شد" else "$offsetMinutes دقیقه به تعویق افتاد"
                return ConversationalResponse(
                    replyText = "سرویس یادآورها در دسترس نیست؛ تغییر زمان انجام نشد.",
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        awaitingField = AwaitingField.NONE,
                        slotStatus = SlotFillingStatus.IDLE
                    ),
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            } else {
                val latest = toolRegistry?.getLatestReminder()
                if (latest != null) {
                    if (offsetMinutes == null) {
                        val question = "قرار «${latest.title}» انتخاب شد. چند دقیقه یا ساعت زودتر تنظیم کنم؟"
                        return ConversationalResponse(
                            replyText = question,
                            newState = state.copy(
                                targetReminderId = latest.id,
                                lastMentionedReminderId = latest.id,
                                awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                                pendingRescheduleDirection = -1,
                                lastAssistantQuestion = question
                            ),
                            suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                        )
                    }
                    val res = toolRegistry.rescheduleReminder(latest.id, offsetMinutes = offsetMinutes)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(
                            targetReminderId = latest.id,
                            lastMentionedReminderId = latest.id,
                            awaitingField = AwaitingField.NONE
                        ),
                        executedResult = res
                    )
                }
            }
        }

        // 2c. Reschedule to explicit hour ("ساعت قرار رو بکن ۱۰", "بکن ساعت ۸ صبح", "ساعتش رو بکن ۱۰")
        if (lower.contains("بکن ساعت") || lower.contains("بذار ساعت") || lower.contains("تغییر بده به ساعت") || lower.contains("ساعت رو بکن") || lower.contains("ساعت قرار رو بکن")) {
            val targetHour = extractNumber(lower)
            if (targetHour != null) {
                var hour24 = targetHour
                if ((lower.contains("عصر") || lower.contains("شب") || lower.contains("بعدازظهر")) && hour24 < 12) {
                    hour24 += 12
                } else if (lower.contains("صبح") && hour24 >= 12) {
                    hour24 -= 12
                }

                val targetId = state.lastMentionedReminderId ?: state.targetReminderId ?: toolRegistry?.getLatestReminder()?.id
                if (targetId != null && toolRegistry != null) {
                    val remRes = toolRegistry.getReminderDetails(targetId)
                    val reminder = remRes.data as? ReminderEntity
                    if (reminder != null) {
                        val cal = Calendar.getInstance(timeZone).apply {
                            timeInMillis = reminder.triggerTimestamp
                            set(Calendar.HOUR_OF_DAY, hour24)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                        }
                        if (cal.timeInMillis < referenceTimeMillis) {
                            cal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        val newTs = cal.timeInMillis
                        val j = PersianDateEngine.timestampToJalali(newTs, timeZone)
                        val newDate = j.formatPersianNumeric()
                        val newTime = PersianDateEngine.formatPersianTime(j.hour, j.minute)

                        val res = toolRegistry.rescheduleReminder(
                            id = targetId,
                            newTimestamp = newTs,
                            newDateText = newDate,
                            newTimeText = newTime
                        )
                        return ConversationalResponse(
                            replyText = if (res.success) {
                                "ساعت یادآور «${reminder.title}» به $newDate ساعت $newTime تغییر یافت."
                            } else {
                                "تغییر ساعت انجام نشد: ${res.message}"
                            },
                            newState = state.copy(
                                activeIntent = ConversationIntent.UPDATE_REMINDER,
                                targetReminderId = targetId,
                                lastMentionedReminderId = targetId,
                                awaitingField = AwaitingField.NONE
                            ),
                            executedResult = res,
                            suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                        )
                    }
                } else if (targetId != null) {
                    return ConversationalResponse(
                        replyText = "سرویس یادآورها در دسترس نیست؛ ساعت قرار تغییر نکرد و هیچ تغییری انجام نشد.",
                        newState = state.copy(
                            activeIntent = ConversationIntent.UPDATE_REMINDER,
                            targetReminderId = targetId,
                            lastMentionedReminderId = targetId,
                            awaitingField = AwaitingField.NONE
                        ),
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                }
            }
        }

        // 3. General Rescheduling reference ("نیم ساعت عقب بنداز", "یک ربع به تعویق بنداز", "یک ساعت بعد")
        if (lower.contains("عقب بنداز") || lower.contains("به تعویق") || lower.contains("تاخیر") || lower.contains("دیرتر")) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            val offsetMinutes = extractMinuteOffset(lower)

            if (targetId != null && offsetMinutes == null) {
                val question = "چند دقیقه یا چند ساعت عقب بیندازم؟"
                return ConversationalResponse(
                    replyText = question,
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        targetReminderId = targetId,
                        lastMentionedReminderId = targetId,
                        awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                        pendingRescheduleDirection = 1,
                        lastAssistantQuestion = question
                    ),
                    suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                )
            }
            if (targetId != null && toolRegistry == null) {
                return ConversationalResponse(
                    replyText = "سرویس یادآورها در دسترس نیست؛ تغییر زمان انجام نشد و هیچ تغییری اعمال نشد.",
                    newState = state.copy(awaitingField = AwaitingField.NONE, pendingRescheduleDirection = null)
                )
            }
            if (targetId != null && toolRegistry != null && offsetMinutes != null) {
                val res = toolRegistry.rescheduleReminder(targetId, offsetMinutes = offsetMinutes)
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_REMINDER,
                        awaitingField = AwaitingField.NONE,
                        slotStatus = if (res.success) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                    ),
                    executedResult = res,
                    suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                )
            } else {
                val latest = toolRegistry?.getLatestReminder()
                if (latest != null) {
                    if (offsetMinutes == null) {
                        val question = "قرار «${latest.title}» انتخاب شد. چند دقیقه یا ساعت عقب بیندازم؟"
                        return ConversationalResponse(
                            replyText = question,
                            newState = state.copy(
                                targetReminderId = latest.id,
                                lastMentionedReminderId = latest.id,
                                awaitingField = AwaitingField.RESCHEDULE_OFFSET,
                                pendingRescheduleDirection = 1,
                                lastAssistantQuestion = question
                            ),
                            suggestedReplies = listOf("۱۵ دقیقه", "نیم ساعت", "یک ساعت")
                        )
                    }
                    val res = toolRegistry.rescheduleReminder(latest.id, offsetMinutes = offsetMinutes)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(
                            targetReminderId = latest.id,
                            lastMentionedReminderId = latest.id,
                            awaitingField = AwaitingField.NONE
                        ),
                        executedResult = res
                    )
                }
                return ConversationalResponse(
                    replyText = "یادآور قبلی برای تغییر زمان یافت نشد. کدام یادآور مد نظرتان است؟",
                    newState = state
                )
            }
        }

        // 4. Advance Alert reference ("برای همین قرار نیم ساعت قبل هم اعلان بذار")
        if (lower.contains("اعلان بذار") || lower.contains("هشدار بذار") || lower.contains("پیش‌اعلان") || lower.contains("پیش اعلان")) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            val advanceMins = extractMinuteOffset(lower) ?: 30
            if (targetId != null) {
                if (toolRegistry != null) {
                    val res = toolRegistry.updateAdvanceAlert(targetId, advanceMins)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        executedResult = res,
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                } else {
                    return ConversationalResponse(
                        replyText = "سرویس یادآورها در دسترس نیست؛ پیش‌اعلان تنظیم نشد و هیچ تغییری انجام نشد.",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                }
            }
        }

        // 5. Soft cancellation ("یادآور رو لغو کن ولی قرار رو نگه دار")
        if (lower.contains("لغو کن ولی") || lower.contains("اعلان رو لغو کن") || lower.contains("آلارمش رو بردار") || (lower.contains("لغو") && lower.contains("نگه دار"))) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            if (targetId != null) {
                if (toolRegistry != null) {
                    val res = toolRegistry.deleteOrCancelReminder(targetId, isHardDelete = false)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        executedResult = res,
                        suggestedReplies = listOf("مشاهده یادآورها")
                    )
                } else {
                    return ConversationalResponse(
                        replyText = "سرویس یادآورها در دسترس نیست؛ آلارم لغو نشد و قرار بدون تغییر باقی ماند.",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        suggestedReplies = listOf("مشاهده یادآورها")
                    )
                }
            }
        }

        // 6. Delete reference or ambiguous delete command ("قرارم رو حذف کن", "قرار رو حذف کن", "حذفش کن", "پاک کن")
        // Bulk deletion must be handled by the bulk-action parser below, not mistaken for a request
        // to identify one specific reminder.
        val isBulkDeleteRequest =
            (lower.contains("همه") || lower.contains("تمام") || lower.contains("کلیه")) &&
            (lower.contains("یادآور") || lower.contains("آلارم") || lower.contains("رویداد") || lower.contains("قرار") || lower.contains("هشدار")) &&
            (lower.contains("حذف") || lower.contains("پاک") || lower.contains("لغو") || lower.contains("کنسل"))
        if (!isBulkDeleteRequest && (lower.contains("حذف کن") || lower.contains("پاک کن") || lower.contains("لغو کن"))) {
            val targetId = state.lastMentionedReminderId ?: state.targetReminderId
            if (targetId != null && toolRegistry != null) {
                val res = toolRegistry.deleteOrCancelReminder(targetId, isHardDelete = true)
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                    newState = state.copy(
                        activeIntent = ConversationIntent.DELETE_REMINDER,
                        targetReminderId = if (res.success) null else state.targetReminderId,
                        lastMentionedReminderId = if (res.success) null else state.lastMentionedReminderId,
                        awaitingField = AwaitingField.NONE
                    ),
                    executedResult = res,
                    suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                )
            } else if (toolRegistry != null) {
                val allRes = toolRegistry.searchReminders(onlyActive = true)
                val activeList = (allRes.data as? List<*>)?.filterIsInstance<ReminderEntity>() ?: emptyList()
                if (activeList.size > 1) {
                    val card = ConversationActionCard(
                        type = ActionCardType.SELECT_FROM_MULTIPLE_REMINDERS,
                        candidateReminders = activeList,
                        primaryActionLabel = "حذف این مورد",
                        secondaryActionLabel = "انصراف"
                    )
                    return ConversationalResponse(
                        replyText = "شما چند قرار فعال دارید؛ مایلید کدام‌یک را حذف کنم؟",
                        newState = state.copy(
                            activeIntent = ConversationIntent.DELETE_REMINDER,
                            awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT,
                            candidateReminderIds = activeList.map { it.id }
                        ),
                        actionCard = card,
                        suggestedReplies = activeList.mapIndexed { idx, _ -> "مورد ${when(idx){0->"اول"; 1->"دوم"; else->"سوم"}}" }
                    )
                } else if (activeList.size == 1) {
                    val target = activeList.first()
                    val delRes = toolRegistry.deleteOrCancelReminder(target.id, isHardDelete = true)
                    return ConversationalResponse(
                        replyText = delRes.message,
                        newState = state.copy(
                            activeIntent = ConversationIntent.DELETE_REMINDER,
                            awaitingField = AwaitingField.NONE
                        ),
                        executedResult = delRes,
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                    )
                }
            } else {
                return ConversationalResponse(
                    replyText = "کدام قرار را مایلید حذف کنم؟ عنوان یا زمان آن را بفرمایید.",
                    newState = state.copy(
                        activeIntent = ConversationIntent.DELETE_REMINDER,
                        awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT
                    ),
                    suggestedReplies = listOf("مشاهده یادآورها")
                )
            }
        }

        return null
    }

    /**
     * Handles querying or listing reminders
     */
    private suspend fun handleQueryReminders(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse {
        val allRes = toolRegistry?.searchReminders()
        val reminders = (allRes?.data as? List<*>)?.filterIsInstance<ReminderEntity>() ?: emptyList()

        if (reminders.isEmpty()) {
            return ConversationalResponse(
                replyText = "در حال حاضر هیچ یادآور فعالی در سیستم ثبت نشده است.",
                newState = state.copy(activeIntent = ConversationIntent.QUERY_REMINDERS),
                suggestedReplies = listOf("یادآور جدید")
            )
        }

        val countText = "شما ${PersianDateEngine.toPersianDigits(reminders.size)} یادآور فعال دارید:\n" +
            reminders.take(4).mapIndexed { idx, r ->
                "${idx + 1}. «${r.title}» در ${r.jalaliDateText} ساعت ${r.timeText}"
            }.joinToString("\n")

        return ConversationalResponse(
            replyText = countText,
            newState = state.copy(
                activeIntent = ConversationIntent.QUERY_REMINDERS,
                candidateReminderIds = reminders.map { it.id }
            ),
            suggestedReplies = listOf("یادآور جدید", "اولین مورد را حذف کن")
        )
    }

    /**
     * Handles appointment or reminder actions with disambiguation when multiple match
     */
    private suspend fun handleAppointmentOrReminderAction(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse {
        val lower = input.lowercase().trim()

        // Comprehensive Deletion & Cancellation Handler
        if (lower.contains("حذف") || lower.contains("پاک") || lower.contains("لغو") || lower.contains("کنسل")) {
            // 1. Bulk Delete All:
            val isBulk = (lower.contains("همه") || lower.contains("تمام") || lower.contains("کلیه")) &&
                    (lower.contains("یادآور") || lower.contains("آلارم") || lower.contains("رویداد") || lower.contains("قرار") || lower.contains("هشدار") || lower.contains("رو") || lower.contains("کن") || lower.contains("پاک"))

            if (isBulk) {
                if (lower.contains("فقط آلارم") || lower.contains("ولی قرار") || (lower.contains("آلارم") && lower.contains("نگه دار"))) {
                    val res = toolRegistry?.cancelAllAlarmsOnly() ?: ToolResult(success = false, message = "سرویس یادآورها در دسترس نیست؛ هیچ آلارمی تغییر نکرد.", errorCode = "TOOL_REGISTRY_UNAVAILABLE")
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(activeIntent = ConversationIntent.UPDATE_REMINDER, awaitingField = AwaitingField.NONE),
                        executedResult = res,
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                    )
                } else {
                    val res = toolRegistry?.deleteAllReminders() ?: ToolResult(success = false, message = "سرویس یادآورها در دسترس نیست؛ هیچ یادآوری حذف نشد.", errorCode = "TOOL_REGISTRY_UNAVAILABLE")
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE, targetReminderId = null, lastMentionedReminderId = null),
                        executedResult = res,
                        suggestedReplies = listOf("یادآور جدید")
                    )
                }
            }

            // 2. Delete by Day (Today / Tomorrow):
            if (lower.contains("امروز")) {
                val cal = Calendar.getInstance(timeZone).apply {
                    timeInMillis = referenceTimeMillis
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val startDay = cal.timeInMillis
                val endDay = startDay + 86400000L - 1
                val res = toolRegistry?.deleteRemindersByDate(startDay, endDay, "امروز")
                    ?: ToolResult(success = false, message = "سرویس یادآورها در دسترس نیست؛ یادآورهای امروز حذف نشدند.", errorCode = "TOOL_REGISTRY_UNAVAILABLE")
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                    newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                    executedResult = res,
                    suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                )
            } else if (lower.contains("فردا")) {
                val cal = Calendar.getInstance(timeZone).apply {
                    timeInMillis = referenceTimeMillis
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val startDay = cal.timeInMillis
                val endDay = startDay + 86400000L - 1
                val res = toolRegistry?.deleteRemindersByDate(startDay, endDay, "فردا")
                    ?: ToolResult(success = false, message = "سرویس یادآورها در دسترس نیست؛ یادآورهای فردا حذف نشدند.", errorCode = "TOOL_REGISTRY_UNAVAILABLE")
                return ConversationalResponse(
                    replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                    newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                    executedResult = res,
                    suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                )
            }

            // 3. Delete by Ordinal (First / Last):
            if (lower.contains("اولین") || lower.contains("اول رو") || lower.contains("نخستین")) {
                val active = toolRegistry?.searchReminders(onlyActive = true)?.data as? List<*>
                val first = active?.filterIsInstance<ReminderEntity>()?.firstOrNull()
                if (first != null) {
                    val res = toolRegistry.deleteOrCancelReminder(first.id, isHardDelete = true)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                        executedResult = res,
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                    )
                } else {
                    return ConversationalResponse(
                        replyText = "هیچ یادآور فعالی برای حذف وجود ندارد.",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        suggestedReplies = listOf("یادآور جدید")
                    )
                }
            } else if (lower.contains("آخرین") || lower.contains("آخری رو")) {
                val active = toolRegistry?.searchReminders(onlyActive = true)?.data as? List<*>
                val last = active?.filterIsInstance<ReminderEntity>()?.lastOrNull()
                if (last != null) {
                    val res = toolRegistry.deleteOrCancelReminder(last.id, isHardDelete = true)
                    return ConversationalResponse(
                        replyText = if (res.success) res.message else "عملیات انجام نشد: ${res.message}",
                        newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                        executedResult = res,
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                    )
                } else {
                    return ConversationalResponse(
                        replyText = "هیچ یادآور فعالی برای حذف وجود ندارد.",
                        newState = state.copy(awaitingField = AwaitingField.NONE),
                        suggestedReplies = listOf("یادآور جدید")
                    )
                }
            }

            // 4. Delete by Hour («آلارم ساعت ۷ رو حذف کن»)
            if (lower.contains("ساعت")) {
                val targetHour = extractNumber(lower)
                if (targetHour != null) {
                    val active = (toolRegistry?.searchReminders(onlyActive = true)?.data as? List<*>)?.filterIsInstance<ReminderEntity>() ?: emptyList()
                    val matching = active.filter { rem ->
                        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = rem.triggerTimestamp }
                        val h = cal.get(Calendar.HOUR_OF_DAY)
                        h == targetHour || (h % 12) == (targetHour % 12)
                    }
                    if (matching.size == 1) {
                        val rem = matching.first()
                        val res = toolRegistry?.deleteOrCancelReminder(rem.id, isHardDelete = true)
                        return ConversationalResponse(
                            replyText = if (res?.success == true) {
                                "یادآور ساعت ${rem.timeText} با عنوان «${rem.title}» حذف شد."
                            } else {
                                "حذف یادآور انجام نشد: ${res?.message ?: "سرویس یادآورها در دسترس نیست."}"
                            },
                            newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                            executedResult = res,
                            suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                        )
                    } else if (matching.size > 1) {
                        val card = ConversationActionCard(
                            type = ActionCardType.SELECT_FROM_MULTIPLE_REMINDERS,
                            candidateReminders = matching,
                            primaryActionLabel = "حذف این مورد"
                        )
                        return ConversationalResponse(
                            replyText = "چند یادآور در حوالی ساعت $targetHour وجود دارد. کدام را مایلید حذف کنم؟",
                            newState = state.copy(
                                activeIntent = ConversationIntent.DELETE_REMINDER,
                                awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT,
                                candidateReminderIds = matching.map { it.id }
                            ),
                            actionCard = card,
                            suggestedReplies = matching.mapIndexed { idx, _ -> "مورد ${idx + 1}" }
                        )
                    }
                }
            }

            // 5. Delete by Subject Keyword
            val titleKeyword = extractSubjectForAction(input)
            if (titleKeyword.isNotBlank() && toolRegistry != null) {
                val candidates = toolRegistry.findMatchingReminders(titleKeyword)
                if (candidates.size > 1) {
                    val listText = "چند قرار با عنوان «$titleKeyword» پیدا شد؛ لطفاً مشخص کنید کدام را مایلید حذف کنید:\n" +
                        candidates.mapIndexed { idx, c ->
                            "${idx + 1}. «${c.title}» (${c.jalaliDateText} ساعت ${c.timeText})"
                        }.joinToString("\n")

                    val card = ConversationActionCard(
                        type = ActionCardType.SELECT_FROM_MULTIPLE_REMINDERS,
                        candidateReminders = candidates,
                        primaryActionLabel = "انتخاب و حذف"
                    )

                    return ConversationalResponse(
                        replyText = listText,
                        newState = state.copy(
                            activeIntent = ConversationIntent.DELETE_REMINDER,
                            awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT,
                            candidateReminderIds = candidates.map { it.id },
                            lastAssistantQuestion = listText
                        ),
                        actionCard = card,
                        suggestedReplies = candidates.take(3).mapIndexed { i, _ -> "مورد ${i + 1}" }
                    )
                } else if (candidates.size == 1) {
                    val single = candidates.first()
                    val delRes = toolRegistry.deleteOrCancelReminder(single.id, isHardDelete = true)
                    return ConversationalResponse(
                        replyText = delRes.message,
                        newState = state.copy(
                            activeIntent = ConversationIntent.DELETE_REMINDER,
                            awaitingField = AwaitingField.NONE
                        ),
                        executedResult = delRes,
                        suggestedReplies = listOf("یادآور جدید", "مشاهده یادآورها")
                    )
                } else {
                    return ConversationalResponse(
                        replyText = "هیچ یادآوری با عنوان یا مشخصات «$titleKeyword» برای حذف پیدا نشد.",
                        newState = state.copy(activeIntent = ConversationIntent.DELETE_REMINDER, awaitingField = AwaitingField.NONE),
                        suggestedReplies = listOf("مشاهده یادآورها", "یادآور جدید")
                    )
                }
            }
        }

        // Standard command analysis
        return analyzeAndStartNewRequest(input, state, referenceTimeMillis, timeZone)
    }

    /**
     * Analyzes standard command and starts new request or clarification
     */
    private fun analyzeAndStartNewRequest(
        input: String,
        state: ConversationState,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ConversationalResponse {
        val lowerInput = input.lowercase()
        val isNextYear = lowerInput.contains("سال آینده") || lowerInput.contains("سال بعد")
        val isDaily = lowerInput.contains("هر روز") || lowerInput.contains("روزانه")
        if (isNextYear && isDaily) {
            val currentJalali = PersianDateEngine.timestampToJalali(referenceTimeMillis, timeZone)
            val nextYear = currentJalali.year + 1
            val recurringTime = PersianCommandAnalyzer.analyze(input, referenceTimeMillis, timeZone)
            val hour = recurringTime.hour ?: currentJalali.hour
            val min = recurringTime.minute ?: currentJalali.minute
            val isAmbiguousRecurringTime = recurringTime.validationStatus == ValidationStatus.AMBIGUOUS
            val startTs = PersianDateEngine.jalaliToTimestamp(nextYear, 1, 1, hour, min, timeZone)
            val endTs = PersianDateEngine.jalaliToTimestamp(nextYear, 12, 29, 23, 59, timeZone)
            val startDateText = PersianDateEngine.toPersianDigits(String.format(java.util.Locale.ROOT, "%04d/01/01", nextYear))
            val timeText = PersianDateEngine.formatPersianTime(hour, min)

            var cleanedTitle = input
                .replace("برای هر روز سال آینده", "")
                .replace("برای هر روز سال بعد", "")
                .replace("هر روز سال آینده", "")
                .replace("هر روز سال بعد", "")
                .replace("سال آینده", "")
                .replace("سال بعد", "")
                .replace("برای هر روز", "")
                .replace("هر روز", "")
                .replace("ساعت ۸ صبح", "")
                .replace("ساعت 8 صبح", "")
                .replace("ساعت ۸", "")
                .replace("ساعت 8", "")
                .replace("صبح", "")
                .replace("یادآور تنظیم کن", "")
                .replace("یادآوری تنظیم کن", "")
                .replace("یادآور بذار", "")
                .replace("یادآور ثبت کن", "")
                .replace("یادآوری", "")
                .replace("یادآور", "")
                .replace("تنظیم کن", "")
                .replace("ثبت کن", "")
                .replace("بذار", "")
                .replace("کن", "")
                .replace("لطفاً", "")
                .replace("لطفا", "")
                .trim()

            val hasTitle = cleanedTitle.isNotBlank() && !isGenericSubject(cleanedTitle)

            if (!hasTitle) {
                val q = "حتماً. برای چه کاری یادآوری تنظیم کنم؟"
                val tentative = ParsedTask(
                    title = "یادآوری روزانه",
                    triggerTimestamp = startTs,
                    jalaliDateText = startDateText,
                    timeText = timeText,
                    repeatType = "DAILY",
                    recurrenceEndDate = endTs,
                    hasExplicitTitle = false,
                    hasExplicitTime = recurringTime.hasExplicitTime && !isAmbiguousRecurringTime,
                    hasExplicitDate = true,
                    isAmbiguous = isAmbiguousRecurringTime,
                    clarificationQuestion = recurringTime.clarificationQuestion
                )
                return ConversationalResponse(
                    replyText = q,
                    newState = state.copy(
                        activeIntent = ConversationIntent.CREATE_REMINDER,
                        slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                        awaitingField = AwaitingField.TITLE,
                        draftTask = tentative,
                        lastAssistantQuestion = q
                    ),
                    suggestedReplies = listOf("ورزش صبحگاهی", "مطالعه کتاب", "برنامه‌ریزی روزانه")
                )
            } else {
                val tentative = ParsedTask(
                    title = cleanedTitle,
                    triggerTimestamp = startTs,
                    jalaliDateText = startDateText,
                    timeText = timeText,
                    repeatType = "DAILY",
                    recurrenceEndDate = endTs,
                    hasExplicitTitle = true,
                    hasExplicitTime = recurringTime.hasExplicitTime && !isAmbiguousRecurringTime,
                    hasExplicitDate = true,
                    isAmbiguous = isAmbiguousRecurringTime,
                    clarificationQuestion = recurringTime.clarificationQuestion
                )
                return proceedToConfirmationOrCompletion(tentative, state, referenceTimeMillis)
            }
        }

        val analysis = PersianCommandAnalyzer.analyze(input, referenceTimeMillis, timeZone)

        // Check if target is past
        if (analysis.targetTimestamp != null && analysis.targetTimestamp < referenceTimeMillis && analysis.validationStatus != ValidationStatus.AMBIGUOUS) {
            return ConversationalResponse(
                replyText = analysis.conflictMessage ?: "زمان مشخص‌شده در گذشته قرار دارد؛ لطفاً یک زمان در آینده بفرمایید.",
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    awaitingField = AwaitingField.TIME_OR_DATE
                ),
                suggestedReplies = listOf("فردا ساعت ۱۰ صبح", "امروز ساعت ۶ عصر", "نیم ساعت دیگه")
            )
        }

        val isGeneric = isGenericSubject(analysis.title)
        val hasHour = analysis.hour != null
        val hasTimestamp = analysis.targetTimestamp != null

        // Case A: Missing subject and clock time. Preserve an explicit date if one was supplied.
        if (isGeneric && !hasHour) {
            val initialTs = analysis.targetTimestamp ?: placeholderTimestampForDate(
                analysis.jalaliDateText.takeIf { analysis.hasExplicitDate }, referenceTimeMillis, timeZone
            )
            val j = PersianDateEngine.timestampToJalali(initialTs, timeZone)
            val startDateText = analysis.jalaliDateText ?: j.formatPersianNumeric()
            val timeText = PersianDateEngine.formatPersianTime(j.hour, j.minute)
            val q = "حتماً. برای چه کاری یادآوری تنظیم کنم؟"
            val tentative = ParsedTask(
                title = "یادآوری",
                triggerTimestamp = initialTs,
                jalaliDateText = startDateText,
                timeText = timeText,
                hasExplicitTitle = false,
                hasExplicitTime = analysis.hasExplicitTime,
                hasExplicitDate = analysis.hasExplicitDate
            )
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    awaitingField = AwaitingField.TITLE,
                    draftTask = tentative,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = listOf("باید برم بانک", "جلسه کاری", "تماس با همکار", "خرید نان")
            )
        }

        // Case B: Time specified, but task subject missing. Date remains unresolved unless explicit.
        if (isGeneric && hasHour) {
            val effTs = analysis.targetTimestamp ?: placeholderTimestampForDate(
                analysis.jalaliDateText.takeIf { analysis.hasExplicitDate }, referenceTimeMillis, timeZone,
                analysis.hour, analysis.minute
            )
            val j = PersianDateEngine.timestampToJalali(effTs, timeZone)
            val dateStr = analysis.jalaliDateText ?: j.formatPersianNumeric()
            val effHour = analysis.hour ?: j.hour
            val effMin = analysis.minute ?: j.minute
            val timeStr = PersianDateEngine.formatPersianTime(effHour, effMin)
            val q = if (analysis.hasExplicitDate) "باشه، برای $dateStr ساعت $timeStr چه کاری رو باید یادتون بندازم؟" else "ساعت $timeStr مشخص است؛ چه روزی و برای چه کاری یادآوری کنم؟"
            val tentative = ParsedTask(
                title = "یادآوری",
                triggerTimestamp = effTs,
                jalaliDateText = dateStr,
                timeText = timeStr,
                hasExplicitTitle = false,
                hasExplicitTime = analysis.hasExplicitTime && analysis.validationStatus != ValidationStatus.AMBIGUOUS,
                hasExplicitDate = analysis.hasExplicitDate,
                isAmbiguous = analysis.validationStatus == ValidationStatus.AMBIGUOUS,
                clarificationQuestion = analysis.clarificationQuestion
            )
            if (tentative.isAmbiguous || analysis.validationStatus == ValidationStatus.AMBIGUOUS) {
                val ambiguityQuestion = analysis.clarificationQuestion ?: "منظورتان ساعت ${tentative.timeText.substringBefore(":")} صبح است یا عصر؟"
                return ConversationalResponse(
                    replyText = ambiguityQuestion,
                    newState = state.copy(
                        activeIntent = ConversationIntent.CREATE_REMINDER,
                        slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                        awaitingField = AwaitingField.AMBIGUOUS_HOUR_AM_PM,
                        draftTask = tentative,
                        lastAssistantQuestion = ambiguityQuestion
                    ),
                    suggestedReplies = listOf("صبح", "عصر")
                )
            }
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    awaitingField = AwaitingField.TITLE,
                    draftTask = tentative,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = listOf("باید برم بانک", "جلسه با مدیر", "تماس با دکتر")
            )
        }

        // Case C: Task subject specified, but clock time is missing.
        if (!isGeneric && !hasHour) {
            val initialTs = analysis.targetTimestamp ?: placeholderTimestampForDate(
                analysis.jalaliDateText.takeIf { analysis.hasExplicitDate }, referenceTimeMillis, timeZone
            )
            val j = PersianDateEngine.timestampToJalali(initialTs, timeZone)
            val q = when {
                !analysis.hasExplicitDate -> "برای «${analysis.title}» چه روز و ساعتی یادآوری کنم؟"
                else -> "تاریخ مشخص است؛ یادآوری «${analysis.title}» را چه ساعتی تنظیم کنم؟"
            }
            val tentative = ParsedTask(
                title = analysis.title,
                triggerTimestamp = initialTs,
                jalaliDateText = analysis.jalaliDateText ?: j.formatPersianNumeric(),
                timeText = PersianDateEngine.formatPersianTime(j.hour, j.minute),
                hasExplicitTitle = true,
                hasExplicitTime = analysis.hasExplicitTime,
                hasExplicitDate = analysis.hasExplicitDate
            )
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    awaitingField = AwaitingField.TIME_OR_DATE,
                    draftTask = tentative,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = if (analysis.hasExplicitDate) listOf("ساعت ۹ صبح", "ساعت ۱۸:۰۰", "نیم ساعت دیگه")
                    else listOf("فردا ساعت ۱۰ صبح", "امروز ساعت ۶ عصر", "نیم ساعت دیگه")
            )
        }

        // Case D: Task and clock time specified; date may still be missing or the hour may be ambiguous.
        val effectiveTs = analysis.targetTimestamp ?: placeholderTimestampForDate(
            analysis.jalaliDateText.takeIf { analysis.hasExplicitDate }, referenceTimeMillis, timeZone,
            analysis.hour, analysis.minute
        )
        val j = PersianDateEngine.timestampToJalali(effectiveTs, timeZone)
        val isAmbiguous = analysis.validationStatus == ValidationStatus.AMBIGUOUS
        val tentative = ParsedTask(
            title = analysis.title,
            triggerTimestamp = effectiveTs,
            jalaliDateText = analysis.jalaliDateText ?: j.formatPersianNumeric(),
            timeText = if (analysis.hasExplicitTime && analysis.hour != null)
                PersianDateEngine.formatPersianTime(analysis.hour, analysis.minute ?: 0)
            else PersianDateEngine.formatPersianTime(j.hour, j.minute),
            isAmbiguous = isAmbiguous,
            clarificationQuestion = analysis.clarificationQuestion,
            hasExplicitTitle = true,
            hasExplicitTime = analysis.hasExplicitTime && !isAmbiguous,
            hasExplicitDate = analysis.hasExplicitDate
        )

        if (isAmbiguous) {
            val q = analysis.clarificationQuestion ?: "منظورتان ساعت ${tentative.timeText.substringBefore(":")} صبح است یا عصر؟"
            return ConversationalResponse(
                replyText = q,
                newState = state.copy(
                    activeIntent = ConversationIntent.CREATE_REMINDER,
                    slotStatus = SlotFillingStatus.COLLECTING_SLOTS,
                    awaitingField = AwaitingField.AMBIGUOUS_HOUR_AM_PM,
                    draftTask = tentative,
                    lastAssistantQuestion = q
                ),
                suggestedReplies = listOf("صبح", "عصر")
            )
        }

        return proceedToConfirmationOrCompletion(tentative, state, referenceTimeMillis)

        // Case E: Ready to execute / confirm
        return proceedToConfirmationOrCompletion(tentative, state, referenceTimeMillis)
    }

    private fun handleAppSettingCommand(input: String, state: ConversationState): ConversationalResponse? {
        val lower = input.lowercase().trim()

        // 1. Advance alert default change ("پیش‌اعلان رو بکن ۳۰ دقیقه", "پیش‌اعلان ۳۰ دقیقه", "از این به بعد تمام یادآورهام رو ۱۵ دقیقه قبل هم اطلاع بده", "پیش‌اعلان رو بردار")
        if (lower.contains("پیش‌اعلان") || lower.contains("پیش اعلان") || ((lower.contains("از این به بعد") || lower.contains("همیشه") || lower.contains("به صورت پیش‌فرض")) && lower.contains("قبل"))) {
            if (lower.contains("غیرفعال") || lower.contains("بردار") || lower.contains("خاموش") || lower.contains("صفر") || lower.contains("حذف")) {
                val toolRes = toolRegistry?.updateDefaultAdvanceAlert(0)
                return ConversationalResponse(
                    replyText = toolRes?.let { if (it.success) it.message else "تغییر تنظیمات انجام نشد: ${it.message}" } ?: "سرویس تنظیمات در دسترس نیست؛ تغییری اعمال نشد.",
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_APP_SETTINGS,
                        awaitingField = AwaitingField.NONE,
                        slotStatus = if (toolRes?.success == true) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                    ),
                    executedResult = toolRes,
                    suggestedReplies = listOf("مشاهده تنظیمات", "یادآور جدید")
                )
            }
            val mins = extractMinuteOffset(lower) ?: extractNumber(lower) ?: 15
            val toolRes = toolRegistry?.updateDefaultAdvanceAlert(mins)
            val msg = toolRes?.let { if (it.success) it.message else "تغییر پیش‌اعلان انجام نشد: ${it.message}" } ?: "سرویس تنظیمات در دسترس نیست؛ پیش‌اعلان ${mins} دقیقه تنظیم نشد."
            return ConversationalResponse(
                replyText = msg,
                newState = state.copy(
                    activeIntent = ConversationIntent.UPDATE_APP_SETTINGS,
                    awaitingField = AwaitingField.NONE,
                    slotStatus = if (toolRes?.success == true) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                ),
                executedResult = toolRes,
                suggestedReplies = listOf("مشاهده تنظیمات", "یادآور جدید", "پوسته تاریک را فعال کن")
            )
        }

        // 2. Theme changes ("تم رو تاریک کن", "پوسته روشن", "حالت شب رو فعال کن", "حالت تاریک")
        if (lower.contains("تم") || lower.contains("پوسته") || lower.contains("حالت شب") || lower.contains("حالت روز") || lower.contains("حالت تاریک") || lower.contains("حالت روشن")) {
            val isDark = lower.contains("تاریک") || lower.contains("شب") || lower.contains("dark")
            val isLight = lower.contains("روشن") || lower.contains("روز") || lower.contains("light")
            if (isDark || isLight) {
                val mode = if (isDark) "تاریک" else "روشن"
                val toolRes = toolRegistry?.updateAppTheme(mode)
                val msg = toolRes?.let { if (it.success) it.message else "تغییر تم انجام نشد: ${it.message}" } ?: "سرویس تنظیمات در دسترس نیست؛ تم $mode تغییر نکرد."
                return ConversationalResponse(
                    replyText = msg,
                    newState = state.copy(
                        activeIntent = ConversationIntent.UPDATE_APP_SETTINGS,
                        awaitingField = AwaitingField.NONE,
                        slotStatus = if (toolRes?.success == true) SlotFillingStatus.COMPLETED else SlotFillingStatus.IDLE
                    ),
                    executedResult = toolRes,
                    suggestedReplies = listOf("مشاهده تنظیمات", "یادآور جدید")
                )
            }
        }

        return null
    }

    private fun handleUserRoutineStatement(input: String, state: ConversationState): ConversationalResponse? {
        if (isConsultationOrAdviceQuestion(input)) return null

        val lower = input.lowercase().trim()
        val isRoutineWord = lower.contains("معمولاً") || lower.contains("معمولا") || lower.contains("عادت دارم") || lower.contains("عادتمه") || lower.contains("برنامه هر روزمه")
        val isWakeUp = lower.contains("بیدار") || lower.contains("پامیشم") || lower.contains("پا میشم") || lower.contains("بلند میشم")
        val isSleep = lower.contains("می‌خوابم") || lower.contains("میخوابم") || lower.contains("خوابم")

        // User says e.g. "من معمولاً ساعت ۸ صبح بیدار میشم"
        if ((isRoutineWord && (isWakeUp || isSleep)) || (isWakeUp && !lower.contains("یادم بنداز") && !lower.contains("تنظیم کن") && !lower.contains("زنگ بذار"))) {
            val hour = extractNumber(lower) ?: 8
            val formattedTime = PersianDateEngine.formatPersianTime(hour, 0)
            val key = if (isSleep) "ساعت_خواب" else "ساعت_بیداری"
            val toolRes = toolRegistry?.saveUserMemoryPreference(key, formattedTime, if (isSleep) "ساعت معمول خواب" else "ساعت معمول بیداری")
            val label = if (isSleep) "خوابیدن" else "بیداری"
            val reply = toolRes?.message ?: "سرویس ذخیره‌سازی ترجیحات در دسترس نیست؛ ساعت $label در حافظه ثبت نشد."
            return ConversationalResponse(
                replyText = reply,
                newState = state.copy(
                    activeIntent = ConversationIntent.USER_ROUTINE_DECLARATION,
                    awaitingField = AwaitingField.NONE,
                    slotStatus = SlotFillingStatus.IDLE
                ),
                executedResult = toolRes,
                suggestedReplies = listOf("به نظرت چه ساعتی ورزش کنم؟", "یادآور جدید", "برنامه فردا چیه؟")
            )
        }
        return null
    }

    private fun isConsultationOrAdviceQuestion(input: String): Boolean {
        val lower = input.lowercase().trim()
        if (lower.startsWith("سلام") || lower.startsWith("درود") || lower.contains("حالت چطوره") || lower.contains("چطوری") || lower.contains("خوبی")) return false
        val adviceTriggers = listOf(
            "به نظرت", "نظر شما", "خوبه؟", "خوبه", "مناسبه", "مناسب است", "مناسب نیست", "چه ساعتی",
            "پیشنهاد میدی", "پیشنهادت چیه", "مشورت", "درسته؟", "صحیحه؟",
            "کم‌خوابی", "۴ ساعت بخوابم", "4 ساعت بخوابم", "۱۲ شب ورزش", "12 شب ورزش",
            "خوابم کمه", "خستگی", "خواب کافی", "کی ورزش کنم"
        )
        return adviceTriggers.any { lower.contains(it) } || (lower.contains("چطوره") && !lower.contains("حالت چطوره"))
    }

    // Helper functions
    private fun isAppointmentOrReminderAction(input: String): Boolean {
        val clean = input.trim().lowercase()

        // 1. Explicit Exclusions: Consultation, user habits, everyday states, feelings, or exams without explicit reminder commands
        if (isConsultationOrAdviceQuestion(clean)) return false
        if (clean.contains("معمولاً") || clean.contains("معمولا") || clean.contains("عادت دارم") || clean.contains("همیشه")) return false
        if (clean.contains("به نظرت") || clean.contains("خوبه؟") || clean.contains("خوبه") || clean.contains("چطوره") || clean.contains("درسته")) return false
        if (clean.contains("ساعت چنده") || clean.contains("ساعت چند است") || clean.contains("الان ساعت چنده")) return false

        // Emotional & physiological states without explicit reminder imperative
        val isEmotionalOrPhysiological = clean.contains("خسته‌ام") || clean.contains("خسته ام") ||
                clean.contains("استراحت کنم") || clean.contains("حالم") || clean.contains("حالم خوب نیست") ||
                clean.contains("حالم بده") || clean.contains("انرژی ندارم") || clean.contains("کسلم") ||
                clean.contains("سلامتی‌ام") || clean.contains("سلامتیم")

        // Routine / personal life statements / stative plans without explicit reminder imperative
        val isRoutineOrPersonalStatement = clean.contains("امتحان دارم") || clean.contains("کنکور دارم") ||
                clean.contains("درس دارم") || clean.contains("سفر می‌روم") || clean.contains("سفر میرم") ||
                clean.contains("مسافرت میرم") || clean.contains("مطالعه کنم") || clean.contains("کتاب بخونم") ||
                clean.contains("بیدار میشم") || clean.contains("بیدار می‌شوم") || clean.contains("بیدار شوم") ||
                clean.contains("می‌خوابم") || clean.contains("میخوابم") ||
                clean.contains("تماس دارم") || clean.contains("جلسه دارم") || clean.contains("قرار دارم") ||
                clean.contains("صحبت کنم") || clean.contains("حرف بزنم") ||
                clean.contains("تماس میگیرم") || clean.contains("تماس می‌گیرم") ||
                clean.contains("جلسه میگیرم") || clean.contains("جلسه می‌گیرم") ||
                (clean.contains("باید") && (clean.contains("بیدار") || clean.contains("برم") || clean.contains("صحبت") || clean.contains("استراحت")))

        // 2. Explicit Positive Imperatives for creating or configuring reminders
        val directTriggers = listOf(
            "یادم بنداز", "یادم بیار", "یادم نره", "یادم باشه", "یادم بمونه",
            "یادآوری کن", "یادآور بذار", "یاداور بذار", "یادآور بگذار", "یادآور تنظیم کن", "یادآور ثبت کن", "یادآور بساز",
            "یک یادآور", "یه یادآور", "یک یادآوری", "یه یادآوری",
            "قرار بذار", "قرار بگذار", "قرار تنظیم کن", "قرار ثبت کن",
            "جلسه بذار", "جلسه بگذار", "جلسه تنظیم کن", "جلسه ثبت کن",
            "وقت دکتر بذار", "وقت دکتر تنظیم کن", "وقت دکتر ثبت کن",
            "آلارم بذار", "آلارم بگذار", "آلارم تنظیم کن", "آلارم ست کن",
            "هشدار بذار", "هشدار بگذار", "هشدار تنظیم کن", "هشدار ست کن",
            "زنگ بذار", "زنگ بگذار", "زنگ تنظیم کن", "زنگ ساعت", "کوک کن",
            "بیدارم کن", "منو بیدار کن", "من رو بیدار کن"
        )

        val hasDirectTrigger = directTriggers.any { clean.contains(it) }

        val modTriggers = listOf(
            "عقب بنداز", "جلو بنداز", "عوض کن", "تغییر بده", "منتقل کن", "به تعویق بنداز", "موکول کن", "تاخیر بنداز",
            "حذف کن", "پاک کن", "لغو کن", "کنسل کن", "انجام شد", "تکمیل شد", "پیش‌اعلان", "اعلان بذار"
        )

        val hasModTrigger = modTriggers.any { clean.contains(it) }

        // General explicit scheduling verbs ("تنظیم کن", "ثبت کن") when applied to a task, reminder, alarm, appointment, or specific hour
        val hasGeneralScheduleVerb = (clean.contains("تنظیم کن") || clean.contains("ثبت کن") || clean.contains("ست کن")) &&
                (clean.contains("ساعت") || clean.contains("فردا") || clean.contains("یادآور") || clean.contains("قرار") || clean.contains("جلسه") || clean.contains("آلارم") || clean.contains("برام") || clean.contains("وقت دکتر"))

        // Concise calendar entry shorthand: e.g. "سه‌شنبه ساعت ۱۹ تماس با همکار"
        val hasWeekday = clean.contains("شنبه") || clean.contains("یکشنبه") || clean.contains("دوشنبه") ||
                clean.contains("سه‌شنبه") || clean.contains("چهارشنبه") || clean.contains("پنجشنبه") || clean.contains("جمعه")
        val hasClockTime = clean.contains("ساعت") && Regex("""ساعت\s*[\d۰-۹]{1,2}""").containsMatchIn(clean)
        val isConciseCalendarEntry = hasWeekday && hasClockTime && !isEmotionalOrPhysiological && !isRoutineOrPersonalStatement

        if (hasDirectTrigger || hasModTrigger || hasGeneralScheduleVerb || isConciseCalendarEntry) {
            return true
        }

        if (isEmotionalOrPhysiological || isRoutineOrPersonalStatement) {
            return false
        }

        return false
    }

    private fun extractSubjectForAction(input: String): String {
        return input.replace("قرار", "")
            .replace("یادآور", "")
            .replace("رو حذف کن", "")
            .replace("رو پاک کن", "")
            .replace("رو لغو کن", "")
            .replace("رو کنسل کن", "")
            .replace("را حذف کن", "")
            .replace("حذف کن", "")
            .replace("پاک کن", "")
            .replace("لطفاً", "")
            .replace("لطفا", "")
            .trim()
    }

    private fun isGenericSubject(title: String): Boolean {
        var clean = title.trim()
        clean = clean.replace(Regex("""^(?:برای|یک|یه|لطفاً|لطفا)\s+"""), "").trim()
        clean = clean.replace(Regex("""^(?:یک|یه)\s+"""), "").trim()
        if (clean.isBlank() || clean.startsWith("یادآوری ساعت") || clean == "یادآوری" || clean == "یادآور" || clean == "یادآوری بدون عنوان") return true
        val genericExactPhrases = setOf(
            "کاری", "کار", "یه کار", "یه کاری", "یه کاری دارم", "کاری دارم", "کارهایم", "کارهام",
            "یک کاری دارم", "یک کاری", "یک کار", "کارم", "تنظیم کن", "ثبت کن", "بذار", "کن", "یادآور", "یادآوری",
            "یک یادآور", "یه یادآور", "یک یادآوری", "یه یادآوری", "یادآور ثبت کن", "یادآور تنظیم کن"
        )
        if (genericExactPhrases.contains(clean)) return true
        if (clean.startsWith("یه کاری") || clean.startsWith("یک کاری") || clean.startsWith("کاری دارم") || clean.startsWith("یه کار دارم") ||
            clean.startsWith("یک یادآور") || clean.startsWith("یه یادآور") || clean.startsWith("یادآور")) return true
        return false
    }

    private fun isCorrection(input: String): Boolean {
        val lower = input.lowercase().trim()
        return lower.contains("منظورم") || lower.contains("اشتباه شد") || lower.contains("عوضش کن") ||
                (lower.startsWith("نه") && (lower.contains("نبود") || lower.contains("بود") || lower.contains("بلکه") || lower.contains("فردا") || lower.contains("ساعت") || lower.length > 8))
    }

    private fun isPositiveConfirmation(input: String): Boolean {
        val lower = input.lowercase().trim()
        val positives = listOf("آره", "اره", "بله", "ثبت", "تایید", "تأیید", "درسته", "اوکی", "باشه", "تنظیم کن", "ثبتش کن", "انجام بده")
        return positives.any { lower.contains(it) }
    }

    private fun isNegativeRejection(input: String): Boolean {
        val lower = input.lowercase().trim()
        if (isCorrection(input)) return false
        val negatives = listOf("نه", "خیر", "نه خیر", "کنسل", "لغو", "نمیخوام", "انصراف", "ولش کن", "دست نگه دار")
        return negatives.any { lower == it || (lower.startsWith(it) && lower.length <= 10) }
    }

    private fun checkChitChatOrGreeting(input: String): Pair<String, List<String>>? {
        val lower = input.lowercase().trim()
        if (lower.startsWith("سلام") || lower.startsWith("درود") || lower.contains("صبح بخیر") || lower.contains("عصر بخیر") || lower.contains("شب بخیر")) {
            return Pair("سلام! من یادت، دستیار هوشمند شما هستم. چطور می‌تونم کمکتون کنم؟", listOf("فردا ساعت ۱۰ یادم بنداز برم بانک", "چه یادآورهایی دارم؟"))
        }
        if (lower.contains("چطوری") || lower.contains("حالت چطوره") || lower.contains("خوبی")) {
            return Pair("ممنون، حالم عالیه! آماده‌ام تا برای کارهاتون یادآوری تنظیم کنم یا با هم درباره موضوعات مختلف گفتگو کنیم.", listOf("یادآور جدید", "یادآورهای امروز"))
        }
        if (lower.contains("کی هستی") || lower.contains("چه کارهایی بلدی") || lower.contains("راهنما")) {
            return Pair("من دستیار هوشمند یادت (Yadet) هستم. شما می‌توانید:\n۱. درباره مسائل روزمره، علمی، یادگیری، فناوری، تندرستی و برنامه‌ریزی با من مشورت و گفتگو کنید.\n۲. قرارها و یادآوری‌هایتان را به فارسی محاوره‌ای ثبت، ویرایش، حذف یا تکرار کنید.\n۳. تنظیمات برنامه مانند پیش‌اعلان و تم را با فرمان صوتی تغییر دهید.", listOf("فردا ۱۰ صبح یادم بنداز", "یک ربع دیگه یادم بنداز"))
        }
        return null
    }

    private fun isQueryRemindersIntent(input: String): Boolean {
        val lower = input.lowercase()
        return lower.contains("چه یادآور") || lower.contains("لیست یادآور") || lower.contains("کارهام چیه") || lower.contains("برنامه‌ام چیه") || lower.contains("چه برنامه‌ای")
    }

    private fun isMeaningfulQuestionOrIdeation(input: String): Boolean {
        val lower = input.lowercase().trim()
        val questionWords = listOf(
            "چطور", "چگونه", "چیست", "کیه", "چیه", "چرا", "کجا", "توضیح", "تعریف",
            "آموزش", "یاد بده", "برنامه‌نویسی", "پایتون", "اندروید", "هوش مصنوعی",
            "ایده", "پیشنهاد", "مقایسه", "فرق", "تفاوت", "راهکار", "راهنمایی",
            "کدام", "آیا", "به نظرت", "نظر شما", "برنامه‌ریزی", "بهره‌وری", "؟", "?"
        )
        if (questionWords.any { lower.contains(it) }) return true
        if (lower.length >= 15 && !lower.contains("الکی") && !lower.contains("فلان بیسار") && !lower.contains("بی‌معنی")) {
            return true
        }
        return false
    }

    private fun extractCleanTitleFromReply(input: String): String {
        var clean = input.trim()
        clean = clean.replace(Regex("^(باید|میخوام|می‌خوام|لطفاً|لطفا|قراره)\\s+"), "")
        clean = clean.replace(Regex("^(برم|بروم)\\s+"), "رفتن به ")
        return clean
    }

    private fun extractNumber(text: String): Int? {
        val clean = PersianDateEngine.toEnglishDigits(text)
        val match = Regex("\\d+").find(clean) ?: return null
        return match.value.toIntOrNull()
    }

    private fun extractMinuteOffset(input: String): Int? {
        if (input.contains("نیم ساعت")) return 30
        if (input.contains("یک ربع") || input.contains("یه ربع")) return 15
        if (input.contains("یک ساعت") || input.contains("یه ساعت")) return 60
        if (input.contains("دو ساعت")) return 120
        val num = extractNumber(input)
        if (num != null && input.contains("دقیقه")) return num
        if (num != null && input.contains("ساعت")) return num * 60
        return null
    }

    private fun parseSimpleColloquialHour(input: String, referenceTimeMillis: Long, timeZone: TimeZone): Long? {
        val num = extractNumber(input) ?: return null
        if (num in 0..23) {
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = referenceTimeMillis
                set(Calendar.HOUR_OF_DAY, num)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            if (cal.timeInMillis < referenceTimeMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        return null
    }

    private fun resolveCandidateSelection(input: String, candidateIds: List<Long>): Long? {
        if (candidateIds.isEmpty()) return null
        if (input.contains("اول") || input.contains("یک") || input.contains("1")) return candidateIds.getOrNull(0)
        if (input.contains("دوم") || input.contains("دو") || input.contains("2")) return candidateIds.getOrNull(1)
        if (input.contains("سوم") || input.contains("سه") || input.contains("3")) return candidateIds.getOrNull(2)
        return candidateIds.firstOrNull()
    }

    private fun isYesterday(timestamp: Long, referenceTimeMillis: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = referenceTimeMillis }
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yDay = cal.get(Calendar.DAY_OF_YEAR)
        val yYear = cal.get(Calendar.YEAR)

        val targetCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return targetCal.get(Calendar.DAY_OF_YEAR) == yDay && targetCal.get(Calendar.YEAR) == yYear
    }
}
