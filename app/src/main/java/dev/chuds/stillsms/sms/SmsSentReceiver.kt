package dev.chuds.stillsms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stub for 0.2 — outbound sentIntent result lands here. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 0.2 will: read resultCode (Activity.RESULT_OK or one of the SmsManager error codes),
        // update the corresponding outbox row in the provider to MESSAGE_TYPE_SENT or
        // MESSAGE_TYPE_FAILED.
    }
}
