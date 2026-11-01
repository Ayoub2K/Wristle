package dev.ayoub.wristle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatHistoryTest {
    private fun chat(question: String) = ChatSession().withUserMessage(1, question).started(2).completed(2, "answer")

    @Test
    fun emptyChatIsNotArchived() {
        val history = listOf(chat("old"))

        assertSame(history, history.withArchived(ChatSession()))
    }

    @Test
    fun newestComesFirst() {
        val archived = listOf(chat("old")).withArchived(chat("new"))

        assertEquals(listOf("new", "old"), archived.map { it.messages.first().text })
    }

    @Test
    fun keepsAtMostMaxHistory() {
        val full = (1..MAX_HISTORY).map { chat("q$it") }

        val archived = full.withArchived(chat("newest"))

        assertEquals(MAX_HISTORY, archived.size)
        assertEquals(
            "newest",
            archived
                .first()
                .messages
                .first()
                .text,
        )
    }

    @Test
    fun pendingChatIsArchivedAsInterruptedAndSeen() {
        val pending = ChatSession().withUserMessage(1, "q").started(2)

        val archived = emptyList<ChatSession>().withArchived(pending).single()

        assertEquals(TurnStatus.FAILED, archived.status)
        assertEquals(true, archived.seen)
    }
}
