package dev.chuds.stillsms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

/** Text-first row, no icons, no ripple. Mirrors still-clock / still-notes' StillMenuItem. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StillMenuItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: TextStyle? = null,
    titleColor: Color = StillColors.SoftWhite,
    subtitleColor: Color = StillColors.DimGray,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val resolvedStyle = style ?: StillTypography.Menu
    val interactionSource = remember { MutableInteractionSource() }
    val resolvedTitleColor = if (enabled) titleColor else StillColors.DimGray

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 10.dp),
    ) {
        Text(text = title, style = resolvedStyle, color = resolvedTitleColor)

        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = StillTypography.Caption,
                color = subtitleColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
