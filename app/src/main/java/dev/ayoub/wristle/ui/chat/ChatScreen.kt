package dev.ayoub.wristle.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.ayoub.wristle.R
import dev.ayoub.wristle.data.Failure
import dev.ayoub.wristle.data.FailureReason
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.ErrorRed

@Composable
fun ChatScreen(
    state: ChatUiState,
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSeen: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var followBottom by remember { mutableStateOf(true) }
    val lastLength =
        state.messages
            .lastOrNull()
            ?.text
            ?.length

    LaunchedEffect(state.hasUnseenResult) {
        if (state.hasUnseenResult) onSeen()
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) followBottom = !listState.canScrollForward
    }
    LaunchedEffect(state.messages.size) { followBottom = true }
    LaunchedEffect(state.messages.size, lastLength, state.failure, state.busy) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (followBottom && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Text(text = state.header, style = MaterialTheme.typography.caption2, color = Color.Gray)
            }
            if (state.messages.isEmpty() && state.failure == null) {
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.chat_hint),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            state.messages.forEach { message ->
                message.paragraphs.forEachIndexed { index, paragraph ->
                    item(key = "${message.id}-$index") {
                        Text(
                            text = paragraph,
                            color = if (message.fromUser) Accent else Color.White,
                            textAlign = if (message.fromUser) TextAlign.End else TextAlign.Start,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            state.failure?.let { failure ->
                item(key = "error") {
                    Text(
                        text = failure.message(),
                        color = ErrorRed,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                item(key = "retry") {
                    Chip(
                        label = { Text(stringResource(R.string.chat_retry)) },
                        onClick = onRetry,
                        colors = ChipDefaults.primaryChipColors(backgroundColor = Accent),
                    )
                }
            }
            item(key = "controls") {
                if (state.busy) {
                    if (state.waitingForFirstWord) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp), indicatorColor = Accent)
                    }
                } else {
                    ChatControls(onMic = onMic, onNewChat = onNewChat, onSettings = onSettings)
                }
            }
        }
    }
}

@Composable
private fun ChatControls(
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val micDescription = stringResource(R.string.chat_ask_by_voice)
    val newChatDescription = stringResource(R.string.chat_new)
    val settingsDescription = stringResource(R.string.chat_open_settings)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(
            onClick = onMic,
            colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
            modifier = Modifier.semantics { contentDescription = micDescription },
        ) {
            Text("🎤")
        }
        Button(
            onClick = onNewChat,
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.semantics { contentDescription = newChatDescription },
        ) {
            Text("＋")
        }
        Button(
            onClick = onSettings,
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.semantics { contentDescription = settingsDescription },
        ) {
            Text("⚙")
        }
    }
}

@Composable
private fun Failure.message(): String =
    when (reason) {
        FailureReason.NO_API_KEY -> stringResource(R.string.error_no_api_key)
        FailureReason.UNAUTHORIZED -> stringResource(R.string.error_unauthorized)
        FailureReason.API -> stringResource(R.string.error_api, detail.orEmpty())
        FailureReason.NETWORK -> stringResource(R.string.error_network, detail.orEmpty())
        FailureReason.TIMEOUT -> stringResource(R.string.error_timeout)
        FailureReason.INTERRUPTED -> stringResource(R.string.error_interrupted)
        FailureReason.EMPTY_REPLY -> stringResource(R.string.error_empty_reply)
    }
