package dev.chuds.stillsms.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * sentIntent target for MmsSender. Receives a result code from the carrier handshake:
 *   - Activity.RESULT_OK → flip MESSAGE_BOX from OUTBOX to SENT
 *   - anything else      → flip to FAILED
 *
 * MMS doesn't surface granular failure reasons through this path; the result code is
 * either OK or one of SmsManager.MMS_ERROR_*. We treat everything that isn't OK as a
 * hard failure for now and surface "failed" in the bubble's caption row.
 */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uriString = intent.getStringExtra(MmsSender.EXTRA_MESSAGE_URI)
            ?: intent.dataString
            ?: return
        val uri = Uri.parse(uriString)
        if (resultCode == Activity.RESULT_OK) {
            MmsSender.markSent(context, uri)
        } else {
            MmsSender.markFailed(context, uri)
        }
        intent.getStringExtra(MmsSender.EXTRA_PDU_FILE)?.let { path ->
            runCatching { File(path).delete() }
        }
    }
}
