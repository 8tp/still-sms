package dev.chuds.stillsms.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * WAP_PUSH_DELIVER receiver. Required for the default-SMS role — when we're the default
 * app, inbound MMS notifications (M-Notification.ind WAP push PDUs) come ONLY here.
 *
 * 0.1 is read-only and ignores the WAP push entirely. 0.3 fills in:
 *   - parse the M-Notification.ind to find the content location
 *   - call SmsManager.downloadMultimediaMessage to fetch via the carrier MMSC
 *   - parse the resulting PDU, write parts to content://mms
 *
 * Documented prominently in the README: "Inbound MMS dropped in 0.1 — 0.3 lands the
 * full MMS path. Don't pick still-sms as default if you depend on receiving picture
 * messages today."
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op. 0.3 turns this into a parse + downloadMultimediaMessage flow.
    }
}
