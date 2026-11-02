package dev.ayoub.wristle.ui.relay

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import dev.ayoub.wristle.R
import dev.ayoub.wristle.relay.RelayTestState
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.ErrorRed

/** P2-M2a: shows whether the watch is registered with the relay and how many pings it answered. */
@Composable
fun RelayTestScreen(
    state: RelayTestState,
    onStart: () -> Unit,
) {
    LaunchedEffect(Unit) { if (state is RelayTestState.Idle) onStart() }
    val listState = rememberScalingLazyListState()
    Scaffold {
        ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(stringResource(R.string.relay_test_title), style = MaterialTheme.typography.title3) }
            when (state) {
                RelayTestState.NotConfigured -> item { Info(stringResource(R.string.relay_not_configured)) }
                RelayTestState.Idle, RelayTestState.Connecting -> {
                    item { CircularProgressIndicator(indicatorColor = Accent, modifier = Modifier.padding(8.dp)) }
                    item { Info(stringResource(R.string.relay_connecting)) }
                }
                is RelayTestState.Ready -> {
                    item { Info(stringResource(R.string.relay_ready), color = Accent) }
                    item { Info(stringResource(R.string.relay_watch_id, state.watchId.take(8)), color = Color.Gray) }
                    item { Info(stringResource(R.string.relay_pings, state.pings)) }
                }
                is RelayTestState.Failed -> {
                    item { Info(stringResource(R.string.relay_failed, state.message), color = ErrorRed) }
                    item {
                        Chip(
                            label = { Text(stringResource(R.string.relay_retry)) },
                            onClick = onStart,
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Info(
    text: String,
    color: Color = Color.White,
) {
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.body2,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
