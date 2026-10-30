package dev.ayoub.wristle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.ayoub.wristle.ui.chat.ChatScreen
import dev.ayoub.wristle.ui.chat.ChatViewModel
import dev.ayoub.wristle.ui.settings.SettingsScreen
import dev.ayoub.wristle.ui.settings.SettingsViewModel

enum class Destination { CHAT, SETTINGS }

data class NavigationRequest(
    val destination: Destination,
    val id: Int,
)

@Composable
fun WristleApp(
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    navigationRequest: NavigationRequest?,
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onEditKey: () -> Unit,
) {
    MaterialTheme {
        val navController = rememberSwipeDismissableNavController()
        LaunchedEffect(navigationRequest) {
            val request = navigationRequest ?: return@LaunchedEffect
            navController.popBackStack(Destination.CHAT.name, inclusive = false)
            if (request.destination == Destination.SETTINGS) navController.navigate(Destination.SETTINGS.name)
        }
        SwipeDismissableNavHost(navController = navController, startDestination = Destination.CHAT.name) {
            composable(Destination.CHAT.name) {
                val state by chatViewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onMic = onMic,
                    onNewChat = onNewChat,
                    onRetry = chatViewModel::retry,
                    onSettings = { navController.navigate(Destination.SETTINGS.name) },
                    onSeen = chatViewModel::markSeen,
                )
            }
            composable(Destination.SETTINGS.name) {
                val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onCycleModel = settingsViewModel::cycleModel,
                    onCycleEffort = settingsViewModel::cycleEffort,
                    onSpeakChange = settingsViewModel::setSpeakReplies,
                    onEditKey = onEditKey,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
