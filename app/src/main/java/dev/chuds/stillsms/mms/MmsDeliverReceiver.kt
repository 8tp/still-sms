package dev.chuds.stillsms.mms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import dev.chuds.stillsms.data.BlockListRepository
import dev.chuds.stillsms.data.ContactNameResolver
import dev.chuds.stillsms.data.PreferencesRepository
import dev.chuds.stillsms.notif.NewMessageNotifier
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * WAP_PUSH_DELIVER receiver. The system delivers inbound MMS notifications (M-Notification.ind
 * WAP push PDUs) only to the default-SMS app; we land here, parse the notification to find the
 * carrier MMSC content-location, and ask the framework to fetch the actual M-Retrieve.conf via
 * SmsManager.downloadMultimediaMessage.
 *
 * The download itself happens out-of-process (the modem talks to the carrier); when it
 * completes, the system fires our MmsDownloadReceiver with the resulting PDU written to a
 * FileProvider URI we hand it.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data") ?: return
        val ctx = context.applicationContext

        val notif = runCatching { MmsPduDecoder.parseNotificationInd(pdu) }.getOrNull() ?: return
        val location = notif.contentLocation ?: return
        if (BlockListRepository(ctx).isBlocked(notif.from)) return

        // Insert a placeholder MMS row immediately. We seed thread/address from the
        // notification so download failures still render as inbound in the right thread.
        val placeholderValues = ContentValues().apply {
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.MESSAGE_TYPE, MmsMessageType.M_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, MmsVersion.V1_0)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.SUBJECT, notif.subject ?: "")
        }
        val placeholderUri = ctx.contentResolver.insert(
            Telephony.Mms.CONTENT_URI, placeholderValues,
        ) ?: return
        val threadId = seedInboundMmsAddress(ctx, placeholderUri, notif.from)

        if (!mmsAutoDownloadOnMobile(ctx)) {
            if (threadId > 0 && !notif.from.isNullOrBlank()) {
                val sender = ContactNameResolver(ctx).displayName(notif.from) ?: notif.from
                NewMessageNotifier.post(
                    context = ctx,
                    threadId = threadId,
                    address = notif.from,
                    sender = sender,
                    preview = notif.subject?.takeIf { it.isNotBlank() } ?: "[mms]",
                )
            }
            return
        }

        // Stage a writable download target. We grant temporary read/write to com.android.phone
        // so the modem-side process can stream the carrier's M-Retrieve.conf into our file.
        val dir = File(ctx.cacheDir, "mms_inbox").apply { mkdirs() }
        val downloadFile = File(dir, "${UUID.randomUUID()}.dat")
        downloadFile.createNewFile()
        val downloadUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", downloadFile)
        for (target in listOf("com.android.phone", "com.android.mms.service")) {
            runCatching {
                ctx.grantUriPermission(
                    target,
                    downloadUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        // Hand off the download.
        val downloadIntent = Intent(ctx, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            data = placeholderUri
            putExtra(MmsDownloadReceiver.EXTRA_PLACEHOLDER_URI, placeholderUri.toString())
            putExtra(MmsDownloadReceiver.EXTRA_DOWNLOAD_FILE, downloadFile.absolutePath)
            putExtra(MmsDownloadReceiver.EXTRA_FROM, notif.from)
        }
        // Don't naively .toInt() the row id — the inbox grows monotonically and a
        // long-lived inbox will overflow Int. Hash the URI string instead so two
        // inbound MMS in the same second still get distinct PendingIntent slots.
        val pi = PendingIntent.getBroadcast(
            ctx,
            placeholderUri.toString().hashCode(),
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        runCatching {
            mmsManager(ctx).downloadMultimediaMessage(ctx, location, downloadUri, null, pi)
        }.onFailure {
            markInboundMmsRetrieveFailed(ctx, placeholderUri, notif.from)
            runCatching { downloadFile.delete() }
        }
    }

    private fun mmsManager(context: Context): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun mmsAutoDownloadOnMobile(context: Context): Boolean =
        runCatching {
            runBlocking {
                PreferencesRepository(context).settings.first().mmsAutoDownloadOnMobile
            }
        }.getOrDefault(true)
}
