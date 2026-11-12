package dev.ayoub.wristle.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.ayoub.wristle.R
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.CenteredList

/**
 * Where the API key comes from. Sending it from the computer (`wristle setup`) is the easy way;
 * typing a 100-character key on the watch is the fallback. Turns into a confirmation once a key arrives.
 */
@Composable
fun KeySetupScreen(
    keySuffix: String?,
    onPairComputer: () -> Unit,
    onTypeKey: () -> Unit,
    onDone: () -> Unit,
) {
    CenteredList {
        if (keySuffix != null) {
            item { Text("✓", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
            item { Line(stringResource(R.string.key_setup_saved, keySuffix)) }
            item { Action(stringResource(R.string.key_setup_start), onDone, primary = true) }
            item { Action(stringResource(R.string.key_setup_replace), onTypeKey) }
            return@CenteredList
        }
        item { Text(stringResource(R.string.key_setup_title), style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
        item { Line(stringResource(R.string.key_setup_from_computer)) }
        item { Line("wristle setup", color = Accent, monospace = true) }
        item { Action(stringResource(R.string.key_setup_pair), onPairComputer, primary = true) }
        item { Action(stringResource(R.string.key_setup_type), onTypeKey) }
        item { Line(stringResource(R.string.key_setup_adb), color = Color.Gray, small = true) }
    }
}

@Composable
private fun Line(
    text: String,
    color: Color = Color.White,
    monospace: Boolean = false,
    small: Boolean = false,
) = Text(
    text = text,
    color = color,
    textAlign = TextAlign.Center,
    style = if (small) MaterialTheme.typography.caption2 else MaterialTheme.typography.body2,
    fontFamily = if (monospace) FontFamily.Monospace else null,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
)

@Composable
private fun Action(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) = Chip(
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    onClick = onClick,
    colors = if (primary) ChipDefaults.primaryChipColors(backgroundColor = Accent) else ChipDefaults.secondaryChipColors(),
)
