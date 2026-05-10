package dev.chuds.stillsms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.ui.theme.LocalHaptics
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

/**
 * Lowercase verb — the still-family's button equivalent. Bordered variant gives
 * persistent footer rows a "this is tappable" cue without breaking the monochrome
 * lexicon (1dp Hairline rectangle, no fill, no ripple).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StillVerb(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bordered: Boolean = false,
    color: Color = StillColors.SoftWhite,
    style: TextStyle? = null,
) {
    val source = remember { MutableInteractionSource() }
    val resolvedStyle = style ?: StillTypography.Menu
    val resolvedColor = if (enabled) color else StillColors.DimGray
    val haptics = LocalHaptics.current
    Text(
        text = label,
        style = resolvedStyle,
        color = resolvedColor,
        modifier = modifier
            .then(
                if (bordered) Modifier.border(1.dp, StillColors.Hairline, RectangleShape)
                else Modifier,
            )
            .combinedClickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = {
                    haptics()
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Build a haptic performer from the app preference. */
@Composable
fun rememberHapticsPerformer(enabled: Boolean): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(enabled, haptic) {
        if (enabled) {
            { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        } else {
            { }
        }
    }
}
