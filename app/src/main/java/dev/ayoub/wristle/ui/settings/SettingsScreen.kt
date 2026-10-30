package dev.ayoub.wristle.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import dev.ayoub.wristle.R
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.ErrorRed

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onCycleModel: () -> Unit,
    onCycleEffort: () -> Unit,
    onSpeakChange: (Boolean) -> Unit,
    onEditKey: () -> Unit,
    onDone: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.title3) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_model)) },
                    secondaryLabel = { Text(state.modelLabel) },
                    onClick = onCycleModel,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_effort)) },
                    secondaryLabel = { Text(state.effort) },
                    onClick = onCycleEffort,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = state.speakReplies,
                    onCheckedChange = onSpeakChange,
                    label = { Text(stringResource(R.string.settings_speak)) },
                    toggleControl = {
                        Icon(imageVector = ToggleChipDefaults.switchIcon(checked = state.speakReplies), contentDescription = null)
                    },
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    secondaryLabel = {
                        val suffix = state.apiKeySuffix
                        Text(
                            text =
                                if (suffix == null) {
                                    stringResource(R.string.settings_api_key_missing)
                                } else {
                                    stringResource(R.string.settings_api_key_suffix, suffix)
                                },
                            color = if (suffix == null) ErrorRed else Color.Unspecified,
                        )
                    },
                    onClick = onEditKey,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Button(onClick = onDone, colors = ButtonDefaults.buttonColors(backgroundColor = Accent)) {
                    Text(stringResource(R.string.settings_done))
                }
            }
        }
    }
}
