package dev.ayoub.wristle.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ayoub.wristle.AppContainer
import dev.ayoub.wristle.data.ChatMessage
import dev.ayoub.wristle.data.ChatRepository
import dev.ayoub.wristle.data.Failure
import dev.ayoub.wristle.data.SettingsRepository
import dev.ayoub.wristle.data.TurnStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ChatUiState(
    val header: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val failure: Failure? = null,
    val hasUnseenResult: Boolean = false,
) {
    val waitingForFirstWord: Boolean get() = busy && messages.lastOrNull()?.let { !it.fromUser && it.text.isEmpty() } == true
}

data class ReplyProgress(
    val id: Long,
    val text: String,
    val done: Boolean,
)

class ChatViewModel(
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<ChatUiState> =
        combine(chat.session, settings.settings) { session, prefs ->
            ChatUiState(
                header = "${SettingsRepository.modelLabel(prefs.model)} · ${prefs.effort}",
                messages = session.messages,
                busy = session.busy,
                failure = session.failure,
                hasUnseenResult = session.hasUnseenResult,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatUiState())

    /** The reply being written, for reading it aloud while it streams. */
    val replyProgress: Flow<ReplyProgress> =
        chat.session
            .map { session ->
                session.messages.lastOrNull()?.takeIf { !it.fromUser }?.let {
                    ReplyProgress(it.id, it.text, done = session.status != TurnStatus.PENDING)
                }
            }.filterNotNull()
            .filter { settings.settings.value.speakReplies }
            .distinctUntilChanged()

    val busy: Flow<Boolean> = chat.session.map { it.busy }.distinctUntilChanged()

    val shouldStartFreshChat: Boolean get() = chat.session.value.shouldStartFreshChat

    fun send(text: String) = chat.send(text)

    fun retry() = chat.retry()

    fun newChat() = chat.newChat()

    fun markSeen() = chat.markSeen()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChatViewModel(container.chatRepository, container.settingsRepository) }
            }
    }
}
