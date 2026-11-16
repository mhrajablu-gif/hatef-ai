package com.example.service.conversation

import com.example.data.model.ReminderEntity
import com.example.service.ai.ParsedTask

/**
 * Sender type in conversation
 */
enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Type of action card presented inside the conversational message thread
 */
enum class ActionCardType {
    PREVIEW_NEW_REMINDER,
    CONFIRM_DELETE_REMINDER,
    CONFIRM_UPDATE_REMINDER,
    SELECT_FROM_MULTIPLE_REMINDERS,
    EXECUTION_RESULT
}

/**
 * Visual interactive card embedded in assistant messages
 */
data class ConversationActionCard(
    val type: ActionCardType,
    val task: ParsedTask? = null,
    val targetReminder: ReminderEntity? = null,
    val candidateReminders: List<ReminderEntity> = emptyList(),
    val primaryActionLabel: String = "تأیید و اجرا",
    val secondaryActionLabel: String? = "انصراف",
    val statusMessage: String? = null
)

/**
 * A message in the assistant conversation thread
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionCard: ConversationActionCard? = null,
    val suggestedReplies: List<String> = emptyList()
)

/**
 * High-level intent classified by conversational AI
 */
enum class ConversationIntent {
    GREETING_OR_CHITCHAT,
    INFORMATIONAL_QA,
    CONSULTATION_OR_ADVICE,
    USER_ROUTINE_DECLARATION,
    UPDATE_APP_SETTINGS,
    CREATE_REMINDER,
    UPDATE_REMINDER,
    DELETE_REMINDER,
    QUERY_REMINDERS,
    CONFIRMATION_OR_REJECTION,
    CLARIFICATION_REPLY,
    CORRECTION,
    UNKNOWN
}

/**
 * Current state of task slot-filling in the conversation
 */
enum class SlotFillingStatus {
    IDLE,
    COLLECTING_SLOTS,
    AWAITING_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    CANCELLED
}

/**
 * Specific field the assistant is currently waiting for the user to answer
 */
enum class AwaitingField {
    NONE,
    TITLE,
    TIME_OR_DATE,
    AMBIGUOUS_HOUR_AM_PM,
    RESCHEDULE_OFFSET,
    WHICH_REMINDER_TO_SELECT,
    EXECUTION_CONFIRMATION
}

/**
 * Rich conversational state tracking multi-turn context
 */
data class ConversationState(
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val activeIntent: ConversationIntent = ConversationIntent.GREETING_OR_CHITCHAT,
    val slotStatus: SlotFillingStatus = SlotFillingStatus.IDLE,
    val awaitingField: AwaitingField = AwaitingField.NONE,
    val lastAssistantQuestion: String? = null,
    val draftTask: ParsedTask? = null,
    val targetReminderId: Long? = null,
    val candidateReminderIds: List<Long> = emptyList(),
    val lastMentionedReminderId: Long? = null,
    val turnsCount: Int = 0,
    // +1 delays the selected reminder; -1 moves it earlier while awaiting a duration.
    val pendingRescheduleDirection: Int? = null,
    val pendingRescheduleOffsetMinutes: Int? = null
) {
    fun isWaitingForAnswer(): Boolean = awaitingField != AwaitingField.NONE
}
