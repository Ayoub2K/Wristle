package dev.ayoub.wristle.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatStoreTest {
    private val dir: File = Files.createTempDirectory("chat-store").toFile()
    private val store = ChatStore(File(dir, "chat.json"))

    @Test
    fun roundTripsCompletedChat() {
        val session =
            ChatSession()
                .withUserMessage(1, "weather in Amsterdam")
                .started(2)
                .completed(2, "Sunny, 18 degrees.\nLight wind.")
        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun roundTripsFailureDetail() {
        val session = ChatSession().withUserMessage(1, "q").started(2).failed(2, Failure(FailureReason.API, "529: overloaded"))
        store.save(session)

        assertEquals(Failure(FailureReason.API, "529: overloaded"), store.load().failure)
    }

    @Test
    fun pendingTurnLoadsAsInterrupted() {
        store.save(ChatSession().withUserMessage(1, "q").started(2))

        val loaded = store.load()
        assertEquals(TurnStatus.FAILED, loaded.status)
        assertEquals(FailureReason.INTERRUPTED, loaded.failure?.reason)
        assertEquals(listOf("q"), loaded.messages.map { it.text })
    }

    @Test
    fun corruptFileLoadsEmptyChat() {
        File(dir, "chat.json").writeText("{not json")

        assertEquals(ChatSession(), store.load())
    }

    @Test
    fun roundTripsHistoryWithStartTimes() {
        val history =
            listOf(
                ChatSession(startedAt = 2_000L).withUserMessage(3, "second").started(4).completed(4, "B"),
                ChatSession(startedAt = 1_000L).withUserMessage(1, "first").started(2).completed(2, "A"),
            )
        store.saveHistory(history)

        assertEquals(history, store.loadHistory())
    }

    @Test
    fun missingHistoryLoadsEmpty() {
        assertEquals(emptyList<ChatSession>(), store.loadHistory())
    }
}
