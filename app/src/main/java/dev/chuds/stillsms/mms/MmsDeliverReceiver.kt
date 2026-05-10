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
import java.io.File
import java.util.UUID

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

        // Stage a writable download target. We grant temporary read/write to com.android.phone
        // so the modem-side process can stream the carrier's M-Retrieve.conf into our file.
        val dir = File(ctx.cacheDir, "mms_inbox").apply { mkdirs() }
        val downloadFile = File(dir, "${UUID.randomUUID()}.dat")
        downloadFile.createNewFile()
        val downloadUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", downloadFile)
        ctx.grantUriPermission(
            "com.android.phone",
            downloadUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        // Insert a placeholder MMS row so the thread list shows the inbound message
        // immediately. We'll fill in the parts and address from the carrier response.
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

        // Hand off the download.
        val downloadIntent = Intent(ctx, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_DOWNLOADED
            data = placeholderUri
            putExtra(MmsDownloadReceiver.EXTRA_PLACEHOLDER_URI, placeholderUri.toString())
            putExtra(MmsDownloadReceiver.EXTRA_DOWNLOAD_FILE, downloadFile.absolutePath)
            putExtra(MmsDownloadReceiver.EXTRA_FROM, notif.from)
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            placeholderUri.lastPathSegment?.toIntOrNull() ?: 0,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        runCatching {
            mmsManager(ctx).downloadMultimediaMessage(ctx, location, downloadUri, null, pi)
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
}
