// Hand-rolled router — sealed Route, no NavCompose. Same shape as still-clock / still-notes.
package dev.chuds.stillsms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.chuds.stillsms.data.BlockListRepository
import dev.chuds.stillsms.data.ContactNameResolver
import dev.chuds.stillsms.data.FontPreset
import dev.chuds.stillsms.data.PreferencesRepository
import dev.chuds.stillsms.data.SmsRoleHelper
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.data.Thread
import dev.chuds.stillsms.data.ThreadRepository
import dev.chuds.stillsms.notif.NotificationChannels
import dev.chuds.stillsms.ui.blocklist.BlockListScreen
import dev.chuds.stillsms.ui.components.rememberHapticsPerformer
import dev.chuds.stillsms.ui.settings.SettingsScreen
import dev.chuds.stillsms.ui.theme.LocalHaptics
import dev.chuds.stillsms.ui.theme.LocalStillTypography
import dev.chuds.stillsms.ui.theme.StillColors
import dev.chuds.stillsms.ui.theme.stillTypographyFor
import dev.chuds.stillsms.ui.thread.ThreadScreen
import dev.chuds.stillsms.ui.threads.ThreadListScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private sealed interface Route {
    data object Threads : Route
    data class Thread(val threadId: Long, val address: String) : Route
    data object Settings : Route
    data object BlockList : Route
}

@Composable
fun StillSmsApp(initialThreadId: Long? = null) {
    val context = LocalContext.current.applicationContext
    val activityContext = LocalContext.current

    val contactResolver = remember(context) { ContactNameResolver(context) }
    val threadRepository = remember(context, contactResolver) { ThreadRepository(context, contactResolver) }
    val blockListRepository = remember(context) { BlockListRepository(context) }
    val preferencesRepository = remember(context) { PreferencesRepository(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        NotificationChannels.ensure(context)
    }

    val settingsFlow = remember(preferencesRepository) {
        preferencesRepository.settings.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SmsSettings(),
        )
    }
    val settings by settingsFlow.collectAsState()

    var isDefault by remember { mutableStateOf(SmsRoleHelper.isDefault(activityContext)) }

    // Re-check role on every recomposition keyed by lifecycle resume.
    LaunchedEffect(Unit) {
        isDefault = SmsRoleHelper.isDefault(activityContext)
    }

    val threadsFlow = remember(threadRepository) {
        threadRepository.observeThreads().stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )
    }
    val threads by threadsFlow.collectAsState()

    val blocked by blockListRepository.blocked.collectAsState()

    var route by remember { mutableStateOf<Route>(Route.Threads) }
    LaunchedEffect(initialThreadId, threads) {
        val tid = initialThreadId ?: return@LaunchedEffect
        val match = threads.firstOrNull { it.id == tid } ?: return@LaunchedEffect
        route = Route.Thread(tid, match.address)
    }

    BackHandler(enabled = route !is Route.Threads) {
        route = when (route) {
            Route.Settings, Route.BlockList -> Route.Threads
            is Route.Thread -> Route.Threads
            Route.Threads -> Route.Threads
        }
    }

    val typography = remember(settings.fontPreset) { stillTypographyFor(settings.fontPreset) }
    val hapticsPerformer = rememberHapticsPerformer(settings.hapticsEnabled)

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* If denied, NewMessageNotifier is a no-op. */ }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) contactResolver.invalidate()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = SmsRoleHelper.isDefault(activityContext)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                activityContext, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val readContactsGranted = ContextCompat.checkSelfPermission(
            activityContext, Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!readContactsGranted) contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    fun requestRole() {
        val intent = SmsRoleHelper.requestRoleIntent(activityContext) ?: return
        roleLauncher.launch(intent)
    }

    CompositionLocalProvider(
        LocalStillTypography provides typography,
        LocalHaptics provides hapticsPerformer,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(StillColors.OledBlack)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "route",
            ) { current ->
                when (current) {
                    Route.Threads -> ThreadListScreen(
                        threads = threads,
                        settings = settings,
                        isDefaultApp = isDefault,
                        onRequestDefault = ::requestRole,
                        onOpenThread = { thread -> route = Route.Thread(thread.id, thread.address) },
                        onLongPressThread = { _ ->
                            // 0.4 will hook this into the archive / block / delete overflow.
                        },
                        onCompose = {
                            // 0.2 wires the contact-picker; 0.1 routes to a stub thread (id = 0)
                            // so the user can see the empty composer screen.
                            route = Route.Thread(0L, "")
                        },
                        onOpenSettings = { route = Route.Settings },
                    )

                    is Route.Thread -> {
                        val messagesFlow = remember(current.threadId, threadRepository) {
                            threadRepository.observeMessages(current.threadId).stateIn(
                                scope = scope,
                                started = SharingStarted.Eagerly,
                                initialValue = emptyList(),
                            )
                        }
                        val messages by messagesFlow.collectAsState()
                        val thread = threads.firstOrNull { it.id == current.threadId }
                        ThreadScreen(
                            title = thread?.displayName?.takeIf { it.isNotBlank() }
                                ?: thread?.address?.takeIf { it.isNotBlank() }
                                ?: current.address.ifBlank { "new" },
                            subtitle = thread?.let { if (it.displayName.isNullOrBlank()) null else it.address },
                            messages = messages,
                            settings = settings,
                            canSend = false, // 0.2 turns this on
                            onSend = { /* 0.2 */ },
                            onAttach = { /* 0.3 */ },
                            onLongPressMessage = { /* 0.2 */ },
                            onOpenContact = {
                                val number = thread?.address ?: current.address
                                if (number.isNotBlank()) {
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("tel:$number")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { activityContext.startActivity(viewIntent) }
                                }
                            },
                            onBack = { route = Route.Threads },
                        )
                        LaunchedEffect(current.threadId) {
                            if (current.threadId > 0) threadRepository.markThreadRead(current.threadId)
                        }
                    }

                    Route.Settings -> SettingsScreen(
                        settings = settings,
                        isDefaultApp = isDefault,
                        onCycleFont = {
                            scope.launch {
                                preferencesRepository.setFontPreset(
                                    when (settings.fontPreset) {
                                        FontPreset.System -> FontPreset.Editorial
                                        FontPreset.Editorial -> FontPreset.Terminal
                                        FontPreset.Terminal -> FontPreset.Grotesk
                                        FontPreset.Grotesk -> FontPreset.System
                                    },
                                )
                            }
                        },
                        onToggle24h = {
                            scope.launch { preferencesRepository.setTwentyFourHour(!settings.twentyFourHour) }
                        },
                        onToggleHaptics = {
                            scope.launch { preferencesRepository.setHapticsEnabled(!settings.hapticsEnabled) }
                        },
                        onToggleGroupMms = {
                            scope.launch { preferencesRepository.setGroupMmsEnabled(!settings.groupMmsEnabled) }
                        },
                        onToggleMmsAutoDownload = {
                            scope.launch { preferencesRepository.setMmsAutoDownloadOnMobile(!settings.mmsAutoDownloadOnMobile) }
                        },
                        onOpenBlockList = { route = Route.BlockList },
                        onExport = { /* 0.4 */ },
                        onRequestDefault = ::requestRole,
                        onBack = { route = Route.Threads },
                    )

                    Route.BlockList -> BlockListScreen(
                        blocked = blocked.toList().sorted(),
                        onAdd = { number -> scope.launch { blockListRepository.add(number) } },
                        onRemove = { number -> scope.launch { blockListRepository.remove(number) } },
                        onBack = { route = Route.Settings },
                    )
                }
            }
        }
    }
}
