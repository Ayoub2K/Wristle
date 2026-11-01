package dev.ayoub.wristle.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.ayoub.wristle.R
import dev.ayoub.wristle.data.ChatMessage
import dev.ayoub.wristle.data.Failure
import dev.ayoub.wristle.data.FailureReason
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.ErrorRed
import kotlinx.coroutines.delay

private val ReplyBubble = Color(0xFF2B2B2B)
private val BubbleShape = RoundedCornerShape(16.dp)

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
            val empty = state.messages.isEmpty() && state.failure == null && !state.busy
            if (empty) {
                item(key = "empty") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MicButton(onMic = onMic, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            text = stringResource(R.string.chat_hint),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            state.messages.forEach { message ->
                message.paragraphs.forEachIndexed { index, paragraph ->
                    item(key = "${message.id}-$index") { Bubble(message, paragraph) }
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
                when {
                    state.waitingForFirstWord -> Thinking()
                    state.busy -> Unit
                    empty -> SmallButton("⚙", stringResource(R.string.chat_open_settings), onSettings)
                    else -> ChatControls(onMic = onMic, onNewChat = onNewChat, onSettings = onSettings)
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    paragraph: String,
) {
    val padding =
        if (message.fromUser) PaddingValues(start = 28.dp, end = 8.dp) else PaddingValues(start = 8.dp, end = 28.dp)
    Box(
        modifier = Modifier.fillMaxWidth().padding(padding).padding(vertical = 2.dp),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = paragraph,
            color = Color.White,
            style = MaterialTheme.typography.body2,
            modifier =
                Modifier
                    .background(if (message.fromUser) Accent else ReplyBubble, BubbleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MicButton(
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onMic,
        colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
        modifier = modifier.size(ButtonDefaults.LargeButtonSize),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = stringResource(R.string.chat_ask_by_voice),
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun SmallButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(ButtonDefaults.SmallButtonSize).semantics { contentDescription = description },
    ) {
        Text(symbol)
    }
}

@Composable
private fun ChatControls(
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SmallButton("＋", stringResource(R.string.chat_new), onNewChat)
        MicButton(onMic = onMic)
        SmallButton("⚙", stringResource(R.string.chat_open_settings), onSettings)
    }
}

@Composable
private fun Thinking() {
    val dots by produceState(0) {
        while (true) {
            delay(DOT_INTERVAL_MS)
            value = (value + 1) % 4
        }
    }
    Row(modifier = Modifier.padding(8.dp)) {
        Text(text = stringResource(R.string.chat_thinking), color = Accent)
        // Fixed width so the text doesn't shift while the dots change.
        Text(text = ".".repeat(dots), color = Accent, modifier = Modifier.width(16.dp))
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

private const val DOT_INTERVAL_MS = 400L
