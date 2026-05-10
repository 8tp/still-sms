package dev.chuds.stillsms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stub for 0.2 — outbound deliveredIntent result lands here. We do not surface delivery
 * receipts in UI (per pact: "no read receipts, delivery receipts, typing indicators"),
 * but we do flip outbound rows from "sent" to "failed" if the deliveredIntent reports
 * a hard failure. */
class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op in 0.1.
    }
}
