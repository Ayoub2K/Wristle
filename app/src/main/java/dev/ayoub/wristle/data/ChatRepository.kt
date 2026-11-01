package dev.ayoub.wristle.data

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

class ChatRepository(
    private val settings: SettingsRepository,
    private val store: ChatStore,
    private val remote: ClaudeRemoteDataSource,
    private val scope: CoroutineScope,
    private val onRequestStarted: () -> Unit,
) {
    private val state = MutableStateFlow(store.load())
    val session: StateFlow<ChatSession> = state.asStateFlow()

    private val diskWriter = Dispatchers.IO.limitedParallelism(1)
    private var job: Job? = null
    private var lastId = state.value.lastMessageId

    fun send(text: String): Boolean {
        val current = state.value
        if (text.isBlank() || current.busy) return false
        update(current.withUserMessage(++lastId, text.trim()))
        startRequest()
        return true
    }

    fun retry() {
        if (state.value.status == TurnStatus.FAILED) startRequest()
    }

    fun newChat() {
        job?.cancel()
        job = null
        update(ChatSession())
    }

    fun markSeen() {
        val current = state.value
        if (!current.seen && !current.busy) update(current.copy(seen = true))
    }

    private fun startRequest() {
        val current = state.value
        val history = current.historyForRequest()
        val replyId = ++lastId
        if (settings.settings.value.apiKey
                .isBlank()
        ) {
            update(current.started(replyId).failed(replyId, Failure(FailureReason.NO_API_KEY)))
            return
        }
        update(current.started(replyId))
        onRequestStarted()
        job =
            scope.launch {
                val buffer = StringBuilder()
                val dirty = Channel<Unit>(Channel.CONFLATED)
                val publisher =
                    launch {
                        for (signal in dirty) {
                            val text = synchronized(buffer) { buffer.toString() }
                            state.update { it.withReplyText(replyId, text) }
                            delay(UI_UPDATE_INTERVAL_MS)
                        }
                    }
                try {
                    val reply =
                        withTimeout(REQUEST_TIMEOUT_MS) {
                            remote.stream(
                                settings = settings.settings.value,
                                history = history,
                                onDelta = { delta ->
                                    synchronized(buffer) { buffer.append(delta) }
                                    dirty.trySend(Unit)
                                },
                            )
                        }
                    publisher.cancel()
                    if (reply.isBlank()) {
                        finish { it.failed(replyId, Failure(FailureReason.EMPTY_REPLY)) }
                    } else {
                        finish { it.completed(replyId, reply) }
                    }
                } catch (e: TimeoutCancellationException) {
                    finish { it.failed(replyId, Failure(FailureReason.TIMEOUT)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnauthorizedException) {
                    finish { it.failed(replyId, Failure(FailureReason.UNAUTHORIZED)) }
                } catch (e: AnthropicServiceException) {
                    finish { it.failed(replyId, Failure(FailureReason.API, "${e.statusCode()}: ${e.message}")) }
                } catch (e: AnthropicIoException) {
                    finish { it.failed(replyId, Failure(FailureReason.NETWORK, e.message)) }
                } catch (e: IOException) {
                    finish { it.failed(replyId, Failure(FailureReason.NETWORK, e.message)) }
                } catch (e: Exception) {
                    finish { it.failed(replyId, Failure(FailureReason.NETWORK, e.message ?: e.javaClass.simpleName)) }
                } finally {
                    publisher.cancel()
                }
            }
    }

    private fun finish(change: (ChatSession) -> ChatSession) {
        state.update(change)
        persist(state.value)
    }

    private fun update(session: ChatSession) {
        state.value = session
        persist(session)
    }

    private fun persist(session: ChatSession) {
        scope.launch(diskWriter) { store.save(session) }
    }

    private companion object {
        const val UI_UPDATE_INTERVAL_MS = 150L
        const val REQUEST_TIMEOUT_MS = 180_000L
    }
}
