package dev.ayoub.wristle.chat

import dev.ayoub.wristle.data.ChatMessage
import dev.ayoub.wristle.data.Failure

/** Where replies come from: the Claude API now, later a bridge to a computer (phase 2). */
interface ChatBackend {
    /** Null when the backend can take a request; otherwise why it can't (e.g. no API key). */
    fun unavailable(): Failure?

    /**
     * Streams a reply to [history], passing text to [onDelta] as it arrives, and returns the final reply.
     * Throws [ChatBackendException] when the request fails.
     */
    suspend fun reply(
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ): String
}

class ChatBackendException(
    val failure: Failure,
    cause: Throwable? = null,
) : Exception(failure.detail ?: failure.reason.name, cause)
