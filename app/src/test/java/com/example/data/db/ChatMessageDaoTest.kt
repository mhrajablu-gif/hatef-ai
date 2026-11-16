package com.example.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessageEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatMessageDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun messagesSurviveReadAndKeepChronologicalOrder() {
        val first = ChatMessageEntity(
            id = "user-1",
            sender = "USER",
            messageText = "سلام",
            timestamp = 100L,
            suggestedReplies = ""
        )
        val second = ChatMessageEntity(
            id = "assistant-1",
            sender = "ASSISTANT",
            messageText = "سلام، چطور کمک کنم؟",
            timestamp = 100L,
            suggestedReplies = "یادآور جدید"
        )

        kotlinx.coroutines.runBlocking {
            dao.insertAll(listOf(first, second))
            assertEquals(listOf(first, second), dao.getAllMessages())
        }
    }

    @Test
    fun replaceAllAtomicallyReplacesOldTranscript() {
        val oldMessage = ChatMessageEntity(
            id = "old",
            sender = "USER",
            messageText = "پیام قدیمی",
            timestamp = 1L
        )
        val newMessage = ChatMessageEntity(
            id = "new",
            sender = "ASSISTANT",
            messageText = "پیام جدید",
            timestamp = 2L
        )

        kotlinx.coroutines.runBlocking {
            dao.insertAll(listOf(oldMessage))
            dao.replaceAll(listOf(newMessage))
            assertEquals(listOf(newMessage), dao.getAllMessages())
        }
    }
}
