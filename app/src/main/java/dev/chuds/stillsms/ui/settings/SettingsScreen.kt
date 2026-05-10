package dev.chuds.stillsms.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.ui.components.StillDivider
import dev.chuds.stillsms.ui.components.StillMenuItem
import dev.chuds.stillsms.ui.components.StillVerb
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.StillTypography

@Composable
fun SettingsScreen(
    settings: SmsSettings,
    isDefaultApp: Boolean,
    onCycleFont: () -> Unit,
    onToggle24h: () -> Unit,
    onToggleHaptics: () -> Unit,
    onToggleGroupMms: () -> Unit,
    onToggleMmsAutoDownload: () -> Unit,
    onOpenBlockList: () -> Unit,
    onExport: () -> Unit,
    onRequestDefault: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                StillVerb(label = "back", onClick = onBack)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "settings",
                    style = StillTypography.Kicker,
                    color = StillColors.MutedWhite,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            StillDivider()

            if (!isDefaultApp) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    StillMenuItem(
                        title = "make default sms app",
                        subtitle = "still-sms must be the system default to read or send messages",
                        onClick = onRequestDefault,
                    )
                }
                StillDivider()
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                StillMenuItem(
                    title = "font preset",
                    subtitle = settings.fontPreset.name.lowercase(),
                    onClick = onCycleFont,
                )
                StillMenuItem(
                    title = "24-hour timestamps",
                    subtitle = if (settings.twentyFourHour) "on" else "off",
                    onClick = onToggle24h,
                )
                StillMenuItem(
                    title = "haptic feedback",
                    subtitle = if (settings.hapticsEnabled) "on" else "off",
                    onClick = onToggleHaptics,
                )
                StillMenuItem(
                    title = "group mms",
                    subtitle = if (settings.groupMmsEnabled) "on" else "off — every foss sms app's biggest bug surface; default off",
                    onClick = onToggleGroupMms,
                )
                StillMenuItem(
                    title = "mms auto-download on mobile data",
                    subtitle = if (settings.mmsAutoDownloadOnMobile) "on" else "off",
                    onClick = onToggleMmsAutoDownload,
                )
                StillMenuItem(
                    title = "blocked numbers",
                    subtitle = "open list",
                    onClick = onOpenBlockList,
                )
                StillMenuItem(
                    title = "export threads",
                    subtitle = "writes still-sms-YYYY-MM-DD.zip with one .txt per thread to documents/",
                    onClick = onExport,
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
