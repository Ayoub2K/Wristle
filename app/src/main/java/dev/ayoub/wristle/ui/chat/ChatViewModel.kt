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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
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

    val repliesToSpeak: Flow<String> = chat.completedReplies.filter { settings.settings.value.speakReplies }

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
