package dev.ayoub.wristle.ui.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.ayoub.wristle.R
import dev.ayoub.wristle.code.CodeSession
import dev.ayoub.wristle.code.CodeState
import dev.ayoub.wristle.code.Project
import dev.ayoub.wristle.relay.PairingState
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.CenteredList
import dev.ayoub.wristle.ui.ErrorRed

/** The Code half: pairing with the computer bridge, then the allowed projects and the session started from the watch. */
@Composable
fun CodeScreen(
    state: PairingState,
    code: CodeState,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onUnpair: () -> Unit,
    onRefresh: () -> Unit,
    onDictate: (Project) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(state is PairingState.Unpaired) { if (state is PairingState.Unpaired) onStartPairing() }
    LaunchedEffect(state is PairingState.Paired) { if (state is PairingState.Paired) onRefresh() }
    DisposableEffect(Unit) { onDispose(onCancelPairing) }
    // A fresh scroll position per pairing step, so a short step doesn't open scrolled into the previous one.
    val listState = key(state::class) { rememberScalingLazyListState(initialCenterItemIndex = 0) }
    CenteredList(state = listState) {
        item { Text("</>", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        when (state) {
            PairingState.NotConfigured -> item { Info(stringResource(R.string.relay_not_configured)) }
            PairingState.Unpaired, PairingState.Working -> {
                item { CircularProgressIndicator(indicatorColor = Accent, modifier = Modifier.padding(8.dp)) }
                item { Info(stringResource(R.string.pair_getting_code)) }
            }
            is PairingState.ShowingCode -> {
                item { Info(stringResource(R.string.pair_run_on_computer)) }
                item { Info("wristle setup", color = Color.Gray, monospace = true) }
                item { Big(state.code) }
            }
            is PairingState.Confirming -> {
                item { Info(stringResource(R.string.pair_same_code)) }
                item { Big(state.sas.chunked(3).joinToString(" ")) }
                if (state.waiting) {
                    item { Info(stringResource(R.string.pair_waiting_for_computer), color = Color.Gray) }
                } else {
                    item { ConfirmButtons(onConfirm = onConfirm, onReject = onReject) }
                }
            }
            PairingState.CancelledOnComputer -> {
                item { Info(stringResource(R.string.pair_cancelled_on_computer), color = ErrorRed) }
                item { ActionChip(stringResource(R.string.relay_retry), onStartPairing) }
            }
            is PairingState.Paired -> {
                code.session?.let { session -> item { SessionCard(session, onStop = onStop, onDismiss = onDismiss) } }
                code.error?.let { error -> item { Info(error, color = ErrorRed) } }
                if (code.session?.running != true) projectItems(code, onRefresh = onRefresh, onDictate = onDictate)
                item {
                    val name = state.computerName
                    Info(
                        if (name == null) stringResource(R.string.pair_paired) else stringResource(R.string.pair_paired_with, name),
                        color = Color.Gray,
                    )
                }
                item { ActionChip(stringResource(R.string.pair_unpair), onUnpair, background = MaterialTheme.colors.surface) }
            }
            is PairingState.Failed -> {
                item { Info(stringResource(R.string.relay_failed, state.message), color = ErrorRed) }
                item { ActionChip(stringResource(R.string.relay_retry), onStartPairing) }
            }
        }
    }
}

private fun ScalingLazyListScope.projectItems(
    code: CodeState,
    onRefresh: () -> Unit,
    onDictate: (Project) -> Unit,
) {
    val projects = code.projects
    when {
        code.noAnswer -> {
            item { Info(stringResource(R.string.code_no_answer), color = ErrorRed) }
            item { Info("wristle serve", color = Color.Gray, monospace = true) }
            item { ActionChip(stringResource(R.string.relay_retry), onRefresh) }
        }
        projects == null -> item { CircularProgressIndicator(indicatorColor = Accent, modifier = Modifier.padding(8.dp)) }
        projects.isEmpty() -> {
            item { Info(stringResource(R.string.code_no_projects)) }
            item { Info("wristle projects add <folder>", color = Color.Gray, monospace = true) }
        }
        else -> {
            item { Info(stringResource(R.string.code_pick_project), color = Color.Gray) }
            items(projects) { project ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = { onDictate(project) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: CodeSession,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(session.project, color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.title3)
        Info("\u201C${session.prompt}\u201D", color = Color.LightGray, maxLines = 3)
        session.model?.let { Info(it, color = Color.Gray, monospace = true) }
        session.lines.forEach { Info(it, color = Color.Gray, style = MaterialTheme.typography.caption2, maxLines = 2) }
        if (session.running) {
            CircularProgressIndicator(indicatorColor = Accent, modifier = Modifier.padding(6.dp).size(28.dp))
            ActionChip(stringResource(R.string.code_stop), onStop, background = ErrorRed)
        } else {
            Info(session.summary.orEmpty(), color = if (session.ok == true) Color.White else ErrorRed)
            session.costUsd?.let {
                Info(
                    stringResource(R.string.code_cost, it),
                    color = Color.Gray,
                    style = MaterialTheme.typography.caption2,
                )
            }
            ActionChip(stringResource(R.string.code_new_question), onDismiss)
        }
    }
}

@Composable
private fun ConfirmButtons(
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        Button(onClick = onReject, colors = ButtonDefaults.secondaryButtonColors()) {
            Text("✕", fontSize = 20.sp)
        }
        Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Accent)) {
            Text("✓", fontSize = 20.sp, color = Color.White)
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    background: Color = Accent,
) = Chip(
    label = { Text(label) },
    onClick = onClick,
    colors = ChipDefaults.primaryChipColors(backgroundColor = background),
)

@Composable
private fun Big(text: String) =
    Text(
        text = text,
        color = Accent,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp),
    )

@Composable
private fun Info(
    text: String,
    color: Color = Color.White,
    monospace: Boolean = false,
    style: TextStyle = MaterialTheme.typography.body2,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}
