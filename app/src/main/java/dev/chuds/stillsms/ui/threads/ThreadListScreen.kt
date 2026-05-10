package dev.chuds.stillsms.ui.threads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.data.Thread
import dev.chuds.stillsms.data.TimeFormat
import dev.chuds.stillsms.ui.components.StillDivider
import dev.chuds.stillsms.ui.components.StillInitialDisc
import dev.chuds.stillsms.ui.components.StillVerb
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

@Composable
fun ThreadListScreen(
    threads: List<Thread>,
    settings: SmsSettings,
    isDefaultApp: Boolean,
    onRequestDefault: () -> Unit,
    onOpenThread: (Thread) -> Unit,
    onLongPressThread: (Thread) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(threads, query) {
        if (query.isBlank()) threads
        else {
            val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
            if (regex == null) threads.filter { containsCi(it, query) }
            else threads.filter { regex.containsMatchIn(it.address) || regex.containsMatchIn(it.snippet) || (it.displayName?.let(regex::containsMatchIn) == true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "still sms",
                    style = StillTypography.Kicker,
                    color = StillColors.MutedWhite,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row {
                    StillVerb(
                        label = if (searchOpen) "close" else "search",
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                    )
                    StillVerb(label = "settings", onClick = onOpenSettings)
                }
            }

            if (searchOpen) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = StillTypography.Body.copy(color = StillColors.SoftWhite),
                    cursorBrush = SolidColor(StillColors.SoftWhite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                StillDivider()
            } else if (!isDefaultApp) {
                DefaultAppBanner(onRequestDefault)
            }

            if (filtered.isEmpty()) {
                EmptyState(query.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(filtered, key = { it.id }) { thread ->
                        ThreadRow(
                            thread = thread,
                            settings = settings,
                            onClick = { onOpenThread(thread) },
                            onLongClick = { onLongPressThread(thread) },
                        )
                        StillDivider()
                    }
                }
            }
        }

        // Footer compose verb. navigationBarsPadding lifts the verb above the system
        // gesture nav bar so it doesn't overlap the home pill / 3-button bar.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(StillColors.OledBlack)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, top = 8.dp),
        ) {
            StillVerb(
                label = "compose",
                bordered = true,
                onClick = onCompose,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: Thread,
    settings: SmsSettings,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        StillInitialDisc(
            displayName = thread.displayName ?: thread.address,
            photoUri = thread.photoUri,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address,
                style = StillTypography.Title.copy(
                    fontWeight = if (thread.read) FontWeight.Light else FontWeight.Normal,
                ),
                color = if (thread.read) StillColors.MutedWhite else StillColors.SoftWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (thread.snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = thread.snippet,
                    style = StillTypography.Small,
                    color = StillColors.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = TimeFormat.listRow(thread.timestamp, settings.twentyFourHour),
            style = StillTypography.Caption,
            color = StillColors.DimGray,
        )
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (filtered) "no matches" else "no messages yet",
            style = StillTypography.Body,
            color = StillColors.MutedWhite,
        )
    }
}

@Composable
private fun DefaultAppBanner(onRequestDefault: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StillColors.CodeSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "still sms isn't your default messaging app.",
            style = StillTypography.Small,
            color = StillColors.MutedWhite,
        )
        Text(
            text = "until it is, threads will not load and inbound messages won't appear here.",
            style = StillTypography.Small,
            color = StillColors.DimGray,
            modifier = Modifier.padding(top = 4.dp),
        )
        StillVerb(
            label = "make default",
            onClick = onRequestDefault,
            bordered = true,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    StillDivider()
}

private fun containsCi(thread: Thread, q: String): Boolean {
    val lower = q.lowercase()
    return thread.address.lowercase().contains(lower) ||
        thread.snippet.lowercase().contains(lower) ||
        (thread.displayName?.lowercase()?.contains(lower) == true)
}
