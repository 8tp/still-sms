package dev.chuds.stillsms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Compose theme for Still SMS. Material3 supplies text/accessibility plumbing only;
 * colors are pinned to the same monochrome tokens as the rest of the still family.
 */
private val StillColorScheme = darkColorScheme(
    background = StillColors.OledBlack,
    surface = StillColors.OledBlack,
    surfaceVariant = StillColors.CodeSurface,
    primary = StillColors.SoftWhite,
    secondary = StillColors.Gray,
    onBackground = StillColors.SoftWhite,
    onSurface = StillColors.SoftWhite,
    onPrimary = StillColors.OledBlack,
    onSecondary = StillColors.OledBlack,
    outline = StillColors.Hairline,
)

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StillColorScheme,
        content = content,
    )
}
