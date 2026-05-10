package dev.chuds.stillsms.ui.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.data.Direction
import dev.chuds.stillsms.data.Message
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.data.TimeFormat
import dev.chuds.stillsms.ui.components.StillDivider
import dev.chuds.stillsms.ui.components.StillInitialDisc
import dev.chuds.stillsms.ui.components.StillVerb
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

@Composable
fun ThreadScreen(
    title: String,
    subtitle: String?,
    photoUri: String?,
    messages: List<Message>,
    settings: SmsSettings,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onAttach: (currentDraft: String) -> Unit,
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

    val rendered = remember(messages, settings.twentyFourHour) { groupMessages(messages) }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                StillInitialDisc(displayName = title, photoUri = photoUri)
                Spacer(modifier = Modifier.padding(start = 12.dp))
                Column {
                    Text(
                        text = title,
                        style = StillTypography.Title.copy(color = StillColors.SoftWhite),
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = StillTypography.Caption,
                            color = StillColors.DimGray,
                        )
                    }
                }
            }
            StillDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            ) {
                items(rendered, key = { it.key }) { item ->
                    MessageRow(item, settings, onLongPress = { onLongPressMessage(item.message) })
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
                onAttach = {
                    onAttach(draft)
                    draft = ""
                },
                enabled = canSend,
            )
        }
    }
}

/**
 * What MessageRow renders. Carries grouping flags so consecutive messages from the same
 * sender can collapse spacing + skip redundant timestamps.
 */
private data class RenderedMessage(
    val message: Message,
    val key: String,
    val showTimestamp: Boolean,
    val isFirstOfGroup: Boolean,
    val isLastOfGroup: Boolean,
)

private fun groupMessages(messages: List<Message>): List<RenderedMessage> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<RenderedMessage>(messages.size)
    val groupGapMs = 5 * 60 * 1000L  // bigger than 5 min apart → new visual group

    for (i in messages.indices) {
        val m = messages[i]
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)

        val prevSameDir = prev != null && prev.direction == m.direction &&
            (m.timestamp - prev.timestamp) < groupGapMs
        val nextSameDir = next != null && next.direction == m.direction &&
            (next.timestamp - m.timestamp) < groupGapMs

        out += RenderedMessage(
            message = m,
            key = "${if (m.isMms) "m" else "s"}-${m.id}",
            // Show timestamp only on the LAST message of a same-direction group,
            // mirroring iMessage / Google Messages cadence.
            showTimestamp = !nextSameDir,
            isFirstOfGroup = !prevSameDir,
            isLastOfGroup = !nextSameDir,
        )
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    item: RenderedMessage,
    settings: SmsSettings,
    onLongPress: () -> Unit,
) {
    val message = item.message
    val outbound = message.direction == Direction.Outbound
    val align = if (outbound) Alignment.End else Alignment.Start

    // Tighter spacing within a group; bigger gap between groups.
    val topPad = if (item.isFirstOfGroup) 10.dp else 2.dp
    val bottomPad = if (item.isLastOfGroup) 6.dp else 0.dp

    val source = remember { MutableInteractionSource() }
    val bubbleShape = bubbleShape(outbound, item.isFirstOfGroup, item.isLastOfGroup)
    val borderColor = if (message.failed) StillColors.Gray else StillColors.Hairline

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topPad, bottom = bottomPad),
        horizontalAlignment = align,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .border(1.dp, borderColor, bubbleShape)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val textColor = when {
                message.failed -> StillColors.Gray
                outbound -> StillColors.SoftWhite
                else -> StillColors.MutedWhite
            }
            if (message.body.isNotBlank()) {
                Text(
                    text = message.body,
                    style = StillTypography.Body,
                    color = textColor,
                    textAlign = if (outbound) TextAlign.End else TextAlign.Start,
                )
            } else if (message.isMms && message.attachmentUri != null) {
                Text(text = "[image]", style = StillTypography.Body, color = textColor)
            } else {
                Text(text = "·", style = StillTypography.Body, color = StillColors.DimGray)
            }
        }
        if (item.showTimestamp) {
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Text(
                text = buildString {
                    append(TimeFormat.longStamp(message.timestamp, settings.twentyFourHour))
                    if (message.failed) append("  ·  failed")
                },
                style = StillTypography.Caption,
                color = StillColors.DimGray,
            )
        }
    }
}

/**
 * Asymmetric corner radii give each bubble a "tail" toward its sender and chain
 * cleanly within a same-sender group (the inner edge corners square off).
 */
private fun bubbleShape(
    outbound: Boolean,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
): RoundedCornerShape {
    val big = 18.dp
    val small = 4.dp
    return if (outbound) {
        RoundedCornerShape(
            topStart = big,
            topEnd = if (isFirstOfGroup) big else small,
            bottomEnd = if (isLastOfGroup) small else small,
            bottomStart = big,
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstOfGroup) big else small,
            topEnd = big,
            bottomEnd = big,
            bottomStart = if (isLastOfGroup) small else small,
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
    Column(
        modifier = Modifier
            .background(StillColors.OledBlack)
            .imePadding()
            .navigationBarsPadding(),
    ) {
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
