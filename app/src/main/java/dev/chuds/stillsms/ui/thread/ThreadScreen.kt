package dev.chuds.stillsms.ui.thread

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.data.Direction
import dev.chuds.stillsms.data.Message
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.data.TimeFormat
import dev.chuds.stillsms.ui.components.StillDivider
import dev.chuds.stillsms.ui.components.StillVerb
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

@Composable
fun ThreadScreen(
    title: String,
    subtitle: String?,
    messages: List<Message>,
    settings: SmsSettings,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onLongPressMessage: (Message) -> Unit,
    onOpenContact: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                StillVerb(label = "back", onClick = onBack)
                Spacer(modifier = Modifier.weight(1f))
                StillVerb(label = "contact", onClick = onOpenContact)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = title,
                    style = StillTypography.Body.copy(color = StillColors.SoftWhite),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = StillTypography.Caption,
                        color = StillColors.DimGray,
                    )
                }
            }
            StillDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { msg -> "${if (msg.isMms) "m" else "s"}-${msg.id}" }) { msg ->
                    MessageRow(msg, settings, onLongPress = { onLongPressMessage(msg) })
                    StillDivider()
                }
            }

            Composer(
                draft = draft,
                onChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                onAttach = onAttach,
                enabled = canSend,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    settings: SmsSettings,
    onLongPress: () -> Unit,
) {
    val outbound = message.direction == Direction.Outbound
    val align = if (outbound) Alignment.End else Alignment.Start
    val textColor = if (outbound) StillColors.SoftWhite else StillColors.MutedWhite
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = align,
    ) {
        if (message.body.isNotBlank()) {
            Text(
                text = message.body,
                style = StillTypography.Body,
                color = if (message.failed) StillColors.Gray else textColor,
                textAlign = if (outbound) TextAlign.End else TextAlign.Start,
            )
        } else if (message.isMms && message.attachmentUri != null) {
            Text(
                text = "[image]",
                style = StillTypography.Body,
                color = textColor,
            )
        } else {
            Text(text = "·", style = StillTypography.Body, color = StillColors.DimGray)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildString {
                append(TimeFormat.longStamp(message.timestamp, settings.twentyFourHour))
                if (message.failed) append("  ·  failed")
            },
            style = StillTypography.Caption,
            color = StillColors.DimGray,
            textAlign = if (outbound) TextAlign.End else TextAlign.Start,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.background(StillColors.OledBlack)) {
        StillDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            StillVerb(label = "+", onClick = onAttach, enabled = enabled)
            BasicTextField(
                value = draft,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = false,
                textStyle = StillTypography.Body.copy(color = StillColors.SoftWhite),
                cursorBrush = SolidColor(StillColors.SoftWhite),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            StillVerb(label = "send", onClick = onSend, enabled = enabled && draft.isNotBlank())
        }
    }
}
