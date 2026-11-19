package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline conversation history. Interactive action cards are intentionally not serialized;
 * the durable transcript keeps sender, text, timestamp and suggested replies.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sender: String,
    val messageText: String,
    val timestamp: Long,
    val suggestedReplies: String = ""
)
