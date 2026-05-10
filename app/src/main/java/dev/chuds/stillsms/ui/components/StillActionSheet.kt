package dev.chuds.stillsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

/**
 * Long-press action sheet — a bottom-anchored card listing lowercase verbs.
 *
 * Built from primitives (Box + Column) rather than Material3's ModalBottomSheet because
 * the latter ships its own scrim / drag-handle / ripple, which would all need overrides
 * to match the pact. A 1-dp Hairline border + OledBlack fill mirrors the rest of the
 * app's surfaces. Tap outside dismisses; tapping a verb invokes it AND dismisses.
 */
data class StillAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun StillActionSheet(
    title: String?,
    actions: List<StillAction>,
    onDismiss: () -> Unit,
) {
    val dismissSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Translucent scrim so the underlying screen reads as "behind" the sheet
            // without violating the OLED-true-black palette anywhere visible.
            .background(StillColors.OledBlack.copy(alpha = 0.72f))
            .clickable(
                interactionSource = dismissSource,
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StillColors.OledBlack)
                .border(1.dp, StillColors.Hairline, RoundedCornerShape(14.dp))
                // Block scrim taps from leaking through the sheet itself.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(verticalArrangement = Arrangement.Top) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = StillTypography.Caption,
                        color = StillColors.DimGray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    StillDivider()
                }
                actions.forEachIndexed { i, action ->
                    val verbSource = remember(action.label) { MutableInteractionSource() }
                    Text(
                        text = action.label,
                        style = StillTypography.Body,
                        color = if (action.destructive) StillColors.Gray else StillColors.SoftWhite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = verbSource,
                                indication = null,
                                onClick = {
                                    action.onClick()
                                    onDismiss()
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    if (i < actions.lastIndex) StillDivider()
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
