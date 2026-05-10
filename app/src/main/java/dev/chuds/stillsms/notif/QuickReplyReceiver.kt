package dev.chuds.stillsms.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stub for 0.2 — RemoteInput-driven quick reply lands here. */
class QuickReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 0.2 will: pull text via RemoteInput.getResultsFromIntent, send via SmsManager,
        // insert outbound row into Telephony.Sms, dismiss the notification.
    }
}
