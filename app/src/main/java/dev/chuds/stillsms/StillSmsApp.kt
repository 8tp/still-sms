// Hand-rolled router — sealed Route, no NavCompose. Same shape as still-clock / still-notes.
package dev.chuds.stillsms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import dev.chuds.stillsms.data.Message
import dev.chuds.stillsms.data.PreferencesRepository
import dev.chuds.stillsms.data.SmsRoleHelper
import dev.chuds.stillsms.data.SmsSettings
import dev.chuds.stillsms.data.Thread
import dev.chuds.stillsms.data.ThreadExporter
import dev.chuds.stillsms.data.ThreadRepository
import dev.chuds.stillsms.mms.MmsSender
import dev.chuds.stillsms.notif.NotificationChannels
import dev.chuds.stillsms.sms.SmsSender
import dev.chuds.stillsms.ui.blocklist.BlockListScreen
import dev.chuds.stillsms.ui.components.StillAction
import dev.chuds.stillsms.ui.components.StillActionSheet
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
    data class Thread(val threadId: Long, val address: String, val prefillBody: String? = null) : Route
    data object Settings : Route
    data object BlockList : Route
}

@Composable
fun StillSmsApp(
    initialThreadId: Long? = null,
    initialAddress: String? = null,
    initialPrefillBody: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val activityContext = LocalContext.current

    val contactResolver = remember(context) { ContactNameResolver(context) }
    val threadRepository = remember(context, contactResolver) { ThreadRepository(context, contactResolver) }
    val blockListRepository = remember(context) { BlockListRepository(context) }
    val preferencesRepository = remember(context) { PreferencesRepository(context) }
    val threadExporter = remember(context, threadRepository) { ThreadExporter(context, threadRepository) }
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
    var pendingComposePrefill by remember { mutableStateOf(initialPrefillBody) }
    LaunchedEffect(initialThreadId, threads) {
        val tid = initialThreadId ?: return@LaunchedEffect
        val match = threads.firstOrNull { it.id == tid } ?: return@LaunchedEffect
        route = Route.Thread(tid, match.address)
    }
    // ACTION_SENDTO landed in MainActivity → resolve to a Thread route by address.
    LaunchedEffect(initialAddress, initialPrefillBody) {
        val addr = initialAddress?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val tid = threadRepository.threadIdForAddress(addr)
        route = Route.Thread(tid, addr, initialPrefillBody)
        pendingComposePrefill = null
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

    // Pending state for the MMS attach flow. We capture (address, draft) when the user
    // taps "+", launch SAF, and on result hand the picked image to MmsSender alongside
    // the draft we captured (used as the MMS caption).
    var pendingAttach by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Long-press menus (one for the thread list, one for inside a thread).
    var longPressedThread by remember { mutableStateOf<Thread?>(null) }
    var longPressedMessage by remember { mutableStateOf<Message?>(null) }
    // Any route change makes a hoisted long-press selection stale. Without this, the
    // sheet would render against a thread/message no longer being viewed.
    LaunchedEffect(route) {
        longPressedThread = null
        longPressedMessage = null
    }
    // One-shot toast-style banner for export results — kept inside StillSmsApp so it
    // surfaces over the SettingsScreen route. Cleared after a few seconds.
    var exportBanner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportBanner) {
        if (exportBanner != null) {
            kotlinx.coroutines.delay(3000)
            exportBanner = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = threadExporter.exportTo(uri)
            exportBanner = if (!result.success) "export failed"
            else if (result.isEmpty) "nothing to export"
            else "exported ${result.threadCount} thread${if (result.threadCount == 1) "" else "s"} (${result.messageCount} messages)"
        }
    }

    val attachPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val pending = pendingAttach
        pendingAttach = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        val (addr, caption) = pending
        scope.launch {
            MmsSender.send(activityContext, addr, caption.takeIf { it.isNotBlank() }, uri)
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingComposePrefill = null
            return@rememberLauncherForActivityResult
        }
        val data = result.data?.data
        if (data == null) {
            pendingComposePrefill = null
            return@rememberLauncherForActivityResult
        }
        // Two shapes work here:
        //   1) An ACTION_PICK against vnd.android.cursor.dir/contact returns a contact URI;
        //      we read the lookup key and pull the primary phone.
        //   2) An ACTION_PICK against vnd.android.cursor.dir/phone_v2 returns a phone-row URI;
        //      address is in CommonDataKinds.Phone.NUMBER.
        scope.launch {
            val number = resolvePickedNumber(activityContext, data)
            if (!number.isNullOrBlank()) {
                val tid = threadRepository.threadIdForAddress(number)
                val prefill = pendingComposePrefill
                pendingComposePrefill = null
                route = Route.Thread(tid, number, prefill)
            } else {
                pendingComposePrefill = null
            }
        }
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
                        onOpenThread = { thread ->
                            val prefill = pendingComposePrefill
                            pendingComposePrefill = null
                            route = Route.Thread(thread.id, thread.address, prefill)
                        },
                        onLongPressThread = { thread -> longPressedThread = thread },
                        onCompose = {
                            val pickIntent = Intent(Intent.ACTION_PICK).apply {
                                type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                            }
                            runCatching { contactPickerLauncher.launch(pickIntent) }
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
                        val resolvedAddress = thread?.address?.takeIf { it.isNotBlank() } ?: current.address
                        // For "new" conversations (no thread row yet) we still want a
                        // name + photo if the address resolves to a contact.
                        val liveInfo = remember(resolvedAddress) {
                            if (thread == null && resolvedAddress.isNotBlank())
                                contactResolver.lookup(resolvedAddress)
                            else null
                        }
                        val resolvedDisplayName = thread?.displayName?.takeIf { it.isNotBlank() }
                            ?: liveInfo?.displayName?.takeIf { it.isNotBlank() }
                        val resolvedPhotoUri = thread?.photoUri ?: liveInfo?.photoUri
                        ThreadScreen(
                            title = resolvedDisplayName ?: resolvedAddress.ifBlank { "new" },
                            subtitle = resolvedDisplayName?.let { resolvedAddress.takeIf { it.isNotBlank() } },
                            photoUri = resolvedPhotoUri,
                            messages = messages,
                            settings = settings,
                            canSend = isDefault && resolvedAddress.isNotBlank(),
                            initialDraft = current.prefillBody.orEmpty(),
                            onSend = { body ->
                                scope.launch {
                                    val sentUri = SmsSender.send(activityContext, resolvedAddress, body)
                                    // Once the row lands, the ContentObserver will flow the
                                    // outbound message back into the LazyColumn — nothing
                                    // else to do here.
                                    if (sentUri == null) {
                                        // No-op for 0.2; 0.4 surfaces a red "send failed" hint.
                                    }
                                }
                            },
                            onAttach = { currentDraft ->
                                if (resolvedAddress.isNotBlank()) {
                                    pendingAttach = resolvedAddress to currentDraft
                                    runCatching { attachPickerLauncher.launch("image/*") }
                                        .onFailure { pendingAttach = null }
                                }
                            },
                            onLongPressMessage = { msg -> longPressedMessage = msg },
                            onOpenContact = {
                                if (resolvedAddress.isNotBlank()) {
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("tel:$resolvedAddress")
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
                        onExport = {
                            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date())
                            runCatching { exportLauncher.launch("still-sms-$today.zip") }
                        },
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

            // Long-press overflow for the thread list. Lives at the StillSmsApp level so
            // the actions can read repositories without prop-drilling them into ThreadListScreen.
            longPressedThread?.let { th ->
                StillActionSheet(
                    title = th.displayName?.takeIf { it.isNotBlank() } ?: th.address,
                    actions = listOf(
                        StillAction(label = "archive") {
                            scope.launch { threadRepository.setArchived(th.id, true) }
                        },
                        StillAction(label = "block") {
                            scope.launch {
                                if (blockListRepository.add(th.address)) {
                                    threadRepository.deleteThread(th.id)
                                }
                            }
                        },
                        StillAction(label = "delete", destructive = true) {
                            scope.launch { threadRepository.deleteThread(th.id) }
                        },
                    ),
                    onDismiss = { longPressedThread = null },
                )
            }

            // Long-press overflow for individual messages.
            longPressedMessage?.let { msg ->
                val clipboard = remember { activityContext.getSystemService(android.content.ClipboardManager::class.java) }
                StillActionSheet(
                    title = msg.body.take(120).takeIf { it.isNotBlank() }
                        ?: if (msg.isMms) "[image]" else null,
                    actions = listOf(
                        StillAction(label = "copy") {
                            val clip = android.content.ClipData.newPlainText("still-sms", msg.body)
                            runCatching { clipboard?.setPrimaryClip(clip) }
                        },
                        StillAction(label = "forward") {
                            // ACTION_SENDTO with the body prefilled — the recipient picker is
                            // either us (we'll handle the smsto: scheme in ComposeActivity) or
                            // another SMS app the user has installed.
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:")
                                putExtra("sms_body", msg.body)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { activityContext.startActivity(intent) }
                        },
                        StillAction(label = "delete", destructive = true) {
                            scope.launch { threadRepository.deleteMessage(msg.id, msg.isMms) }
                        },
                    ),
                    onDismiss = { longPressedMessage = null },
                )
            }

            // One-shot result banner pinned to the top under the status bar.
            exportBanner?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                        .background(
                            StillColors.OledBlack,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        )
                        .border(
                            1.dp,
                            StillColors.Hairline,
                            androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = msg,
                        style = dev.chuds.stillsms.ui.theme.StillTypography.Small,
                        color = StillColors.SoftWhite,
                    )
                }
            }
        }
    }
}

/** Resolve an ACTION_PICK result URI to a phone number string. */
private suspend fun resolvePickedNumber(context: android.content.Context, data: Uri): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        // Phone-row URIs (vnd.android.cursor.item/phone_v2) carry NUMBER directly.
        runCatching {
            resolver.query(
                data,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return@withContext n
                }
            }
        }
        // Fallback: contact URI → look up primary phone.
        runCatching {
            resolver.query(
                data,
                arrayOf(ContactsContract.Contacts._ID),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@withContext null
                val contactId = c.getLong(0)
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId.toString()),
                    "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC, ${ContactsContract.Data._ID} ASC",
                )?.use { phones ->
                    if (phones.moveToFirst()) phones.getString(0) else null
                }
            }
        }.getOrNull()
    }
