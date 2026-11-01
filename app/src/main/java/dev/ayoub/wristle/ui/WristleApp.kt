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
import dev.ayoub.wristle.ui.history.HistoryScreen
import dev.ayoub.wristle.ui.home.CodeComingSoonScreen
import dev.ayoub.wristle.ui.home.HomeScreen
import dev.ayoub.wristle.ui.settings.SettingsScreen
import dev.ayoub.wristle.ui.settings.SettingsViewModel

enum class Destination { HOME, CHAT, CODE, HISTORY, SETTINGS }

data class NavigationRequest(
    val destination: Destination,
    val id: Int,
)

@Composable
fun WristleApp(
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    navigationRequest: NavigationRequest?,
    onOpenChat: () -> Unit,
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onEditKey: () -> Unit,
) {
    MaterialTheme {
        val navController = rememberSwipeDismissableNavController()
        LaunchedEffect(navigationRequest) {
            val request = navigationRequest ?: return@LaunchedEffect
            // Every request starts from home, so swiping back always ends there.
            navController.popBackStack(Destination.HOME.name, inclusive = false)
            if (request.destination != Destination.HOME) navController.navigate(request.destination.name)
        }
        SwipeDismissableNavHost(navController = navController, startDestination = Destination.HOME.name) {
            composable(Destination.HOME.name) {
                HomeScreen(onChat = onOpenChat, onCode = { navController.navigate(Destination.CODE.name) })
            }
            composable(Destination.CHAT.name) {
                val state by chatViewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onMic = onMic,
                    onNewChat = onNewChat,
                    onRetry = chatViewModel::retry,
                    onSettings = { navController.navigate(Destination.SETTINGS.name) },
                    onHistory = { navController.navigate(Destination.HISTORY.name) },
                    onSeen = chatViewModel::markSeen,
                )
            }
            composable(Destination.CODE.name) { CodeComingSoonScreen() }
            composable(Destination.HISTORY.name) {
                val items by chatViewModel.history.collectAsStateWithLifecycle()
                HistoryScreen(
                    items = items,
                    onOpen = { index ->
                        chatViewModel.openFromHistory(index)
                        navController.popBackStack()
                    },
                )
            }
            composable(Destination.SETTINGS.name) {
                val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onCycleModel = settingsViewModel::cycleModel,
                    onCycleEffort = settingsViewModel::cycleEffort,
                    onSpeakChange = settingsViewModel::setSpeakReplies,
                    onLongRepliesChange = settingsViewModel::setLongReplies,
                    onEditKey = onEditKey,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
