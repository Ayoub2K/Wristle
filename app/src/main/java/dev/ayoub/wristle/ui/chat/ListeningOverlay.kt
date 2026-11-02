package dev.ayoub.wristle.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.ayoub.wristle.R
import dev.ayoub.wristle.speech.ListeningState
import dev.ayoub.wristle.ui.Accent

/**
 * Full-screen listening UI on top of the app: a terracotta circle that grows with the voice level and the words
 * recognised so far. Tap to finish, back to cancel.
 */
@Composable
fun ListeningOverlay(
    state: ListeningState,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state is ListeningState.Idle) return
    BackHandler(onBack = onCancel)
    val level = (state as? ListeningState.Listening)?.level ?: 0f
    val pulse by animateFloatAsState(targetValue = 1f + level * 0.35f, animationSpec = spring(), label = "pulse")
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFinish),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(76.dp)
                            .scale(pulse)
                            .background(Accent.copy(alpha = 0.35f), CircleShape),
                )
                Box(modifier = Modifier.size(64.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                    if (state is ListeningState.Processing) {
                        CircularProgressIndicator(indicatorColor = Color.White, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            val partial = (state as? ListeningState.Listening)?.partial.orEmpty()
            Text(
                text = partial.ifBlank { stringResource(R.string.listening) },
                color = if (partial.isBlank()) Color.Gray else Color.White,
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
            if (state is ListeningState.Listening) {
                Text(text = stringResource(R.string.listening_tap_to_send), color = Color.Gray, style = MaterialTheme.typography.caption2)
            }
        }
    }
}
