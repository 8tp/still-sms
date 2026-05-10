package dev.chuds.stillsms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

/**
 * 1-bit initials disc, falling back to the contact's photo when one is available.
 *
 * The pact's "no avatars" line is relaxed here at the user's explicit request: real
 * contact photos sync through ContactsContract when present, and the initials disc is
 * the fallback for everything else.
 */
@Composable
fun StillInitialDisc(
    displayName: String?,
    photoUri: String? = null,
    modifier: Modifier = Modifier,
) {
    val initials = remember(displayName) { initialsOf(displayName) }
    val bitmap = rememberContactPhoto(photoUri)

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, StillColors.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
        } else {
            Text(
                text = initials,
                style = StillTypography.Caption.copy(fontSize = 12.sp),
                color = StillColors.MutedWhite,
            )
        }
    }
}

private fun initialsOf(displayName: String?): String {
    val name = displayName?.trim().orEmpty()
    if (name.isEmpty()) return "·"
    val parts = name.split(' ').filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> "·"
        1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
