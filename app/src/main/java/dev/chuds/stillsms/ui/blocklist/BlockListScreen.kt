package dev.chuds.stillsms.ui.blocklist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.data.BlockListMatcher
import dev.chuds.stillsms.ui.components.StillDivider
import dev.chuds.stillsms.ui.components.StillVerb
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

@Composable
fun BlockListScreen(
    blocked: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    var entry by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

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
                Text(
                    text = "blocked",
                    style = StillTypography.Kicker,
                    color = StillColors.MutedWhite,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            StillDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = entry,
                    onValueChange = {
                        entry = it
                        error = null
                    },
                    singleLine = true,
                    textStyle = StillTypography.Body.copy(color = StillColors.SoftWhite),
                    cursorBrush = SolidColor(StillColors.SoftWhite),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                StillVerb(
                    label = "add",
                    onClick = {
                        if (entry.isNotBlank()) {
                            val normalized = BlockListMatcher.normalize(entry.trim())
                            if (normalized == null) {
                                error = "use +countrycode"
                            } else {
                                onAdd(normalized)
                                entry = ""
                                error = null
                            }
                        }
                    },
                    enabled = entry.isNotBlank(),
                )
            }
            error?.let { message ->
                Text(
                    text = message,
                    style = StillTypography.Caption,
                    color = StillColors.MutedWhite,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            StillDivider()

            if (blocked.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "nothing blocked",
                        style = StillTypography.Body,
                        color = StillColors.MutedWhite,
                    )
                }
            } else {
                LazyColumn {
                    items(blocked, key = { it }) { number ->
                        BlockRow(number, onRemove = { onRemove(number) })
                        StillDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockRow(number: String, onRemove: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = {},
                onLongClick = onRemove,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = number, style = StillTypography.Body, color = StillColors.SoftWhite)
    }
}
