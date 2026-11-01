package dev.ayoub.wristle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ayoub.wristle.AppContainer
import dev.ayoub.wristle.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val modelLabel: String = "",
    val effort: String = "",
    val speakReplies: Boolean = true,
    val longReplies: Boolean = false,
    val apiKeySuffix: String? = null,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> =
        settings.settings
            .map {
                SettingsUiState(
                    modelLabel = SettingsRepository.modelLabel(it.model),
                    effort = it.effort,
                    speakReplies = it.speakReplies,
                    longReplies = it.longReplies,
                    apiKeySuffix = it.apiKey.takeIf { key -> key.isNotBlank() }?.takeLast(4),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    val hasApiKey: Boolean get() =
        settings.settings.value.apiKey
            .isNotBlank()

    fun cycleModel() = settings.cycleModel()

    fun cycleEffort() = settings.cycleEffort()

    fun setSpeakReplies(value: Boolean) = settings.setSpeakReplies(value)

    fun setLongReplies(value: Boolean) = settings.setLongReplies(value)

    fun setApiKey(value: String) = settings.setApiKey(value)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SettingsViewModel(container.settingsRepository) }
            }
    }
}
