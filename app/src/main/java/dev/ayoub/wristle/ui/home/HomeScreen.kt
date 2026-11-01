package dev.ayoub.wristle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.ayoub.wristle.R
import dev.ayoub.wristle.ui.Accent
import dev.ayoub.wristle.ui.AccentDark

/**
 * Start screen: two half moons on the round display, chat on the left and computer control (phase 2)
 * on the right, split by a straight black line.
 */
@Composable
fun HomeScreen(
    onChat: () -> Unit,
    onCode: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Half(
                label = stringResource(R.string.home_chat),
                color = Accent,
                onClick = onChat,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(modifier = Modifier.width(DIVIDER_WIDTH))
            Half(
                label = stringResource(R.string.home_code),
                color = AccentDark,
                onClick = onCode,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "</>", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Half(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    // The round screen clips each full-height half into a half moon.
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(color)
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Same-size slot for both icons, so the two labels line up.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.button,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private val DIVIDER_WIDTH = 6.dp

@Composable
fun CodeComingSoonScreen() {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "</>", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.code_coming_soon),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
