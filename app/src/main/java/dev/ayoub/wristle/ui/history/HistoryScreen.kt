package dev.ayoub.wristle.ui.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import dev.ayoub.wristle.R

data class HistoryItem(
    val title: String,
    val date: String,
)

@Composable
fun HistoryScreen(
    items: List<HistoryItem>,
    onOpen: (Int) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.title3) }
            if (items.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.history_empty),
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            itemsIndexed(items) { index, item ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(item.date) },
                    onClick = { onOpen(index) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}
