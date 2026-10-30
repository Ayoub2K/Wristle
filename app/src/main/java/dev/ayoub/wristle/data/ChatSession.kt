package dev.ayoub.wristle.data

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
) {
    val paragraphs: List<String> by lazy { text.split('\n').filter { it.isNotBlank() } }
}

enum class TurnStatus { IDLE, PENDING, DONE, FAILED }

enum class FailureReason { NO_API_KEY, UNAUTHORIZED, API, NETWORK, TIMEOUT, INTERRUPTED, EMPTY_REPLY }

data class Failure(
    val reason: FailureReason,
    val detail: String? = null,
)

data class ChatSession(
    val messages: List<ChatMessage> = emptyList(),
    val status: TurnStatus = TurnStatus.IDLE,
    val failure: Failure? = null,
    val seen: Boolean = true,
) {
    val busy: Boolean get() = status == TurnStatus.PENDING

    val hasUnseenResult: Boolean
        get() = !seen && (status == TurnStatus.DONE || status == TurnStatus.FAILED)

    val shouldStartFreshChat: Boolean get() = !busy && !hasUnseenResult

    val lastMessageId: Long get() = messages.maxOfOrNull { it.id } ?: 0L

    fun withUserMessage(
        id: Long,
        text: String,
    ): ChatSession = copy(messages = messages + ChatMessage(id, fromUser = true, text = text))

    fun started(replyId: Long): ChatSession =
        copy(
            messages = messages + ChatMessage(replyId, fromUser = false, text = ""),
            status = TurnStatus.PENDING,
            failure = null,
            seen = false,
        )

    fun withReplyText(
        replyId: Long,
        text: String,
    ): ChatSession = copy(messages = messages.map { if (it.id == replyId) it.copy(text = text) else it })

    fun completed(
        replyId: Long,
        text: String,
    ): ChatSession =
        if (messages.none { it.id == replyId }) {
            this
        } else {
            withReplyText(replyId, text).copy(status = TurnStatus.DONE)
        }

    fun failed(
        replyId: Long,
        failure: Failure,
    ): ChatSession =
        if (messages.none { it.id == replyId }) {
            this
        } else {
            copy(
                messages = messages.filterNot { it.id == replyId },
                status = TurnStatus.FAILED,
                failure = failure,
            )
        }

    fun interrupted(): ChatSession {
        if (!busy) return this
        val reply = messages.lastOrNull()?.takeIf { !it.fromUser }
        return if (reply != null) {
            failed(reply.id, Failure(FailureReason.INTERRUPTED))
        } else {
            copy(status = TurnStatus.FAILED, failure = Failure(FailureReason.INTERRUPTED))
        }
    }

    fun historyForRequest(): List<ChatMessage> = messages.filter { it.fromUser || it.text.isNotBlank() }
}
