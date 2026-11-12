package dev.ayoub.wristle.ui.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberRevealState
import dev.ayoub.wristle.R
import dev.ayoub.wristle.ui.CenteredList
import dev.ayoub.wristle.ui.ErrorRed

data class HistoryItem(
    val startedAt: Long,
    val title: String,
    val date: String,
)

@Composable
fun HistoryScreen(
    items: List<HistoryItem>,
    onOpen: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    CenteredList {
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
        // Keyed by start time, so a half-revealed delete action doesn't move to the next chat after a delete.
        itemsIndexed(items, key = { index, item -> if (item.startedAt > 0) item.startedAt else -index - 1L }) { index, item ->
            DeletableChip(item = item, onOpen = { onOpen(index) }, onDelete = { onDelete(index) })
        }
    }
}

/** Swipe left to reveal a delete button; a full swipe deletes right away. */
@OptIn(ExperimentalWearMaterialApi::class, ExperimentalWearFoundationApi::class)
@Composable
private fun DeletableChip(
    item: HistoryItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val revealState = rememberRevealState()
    val deleteLabel = stringResource(R.string.history_delete)
    SwipeToRevealChip(
        revealState = revealState,
        onFullSwipe = onDelete,
        modifier = Modifier.fillMaxWidth(),
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                onClick = onDelete,
                icon = { Icon(SwipeToRevealDefaults.Delete, contentDescription = deleteLabel) },
                label = { Text(deleteLabel) },
            )
        },
        colors = SwipeToRevealDefaults.actionColors(primaryActionBackgroundColor = ErrorRed),
    ) {
        Chip(
            modifier = Modifier.fillMaxWidth(),
            label = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = { Text(item.date) },
            onClick = onOpen,
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}
