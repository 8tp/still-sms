package dev.chuds.stillsms.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stub for 0.2 — "mark read" notification action lands here. */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 0.2 will: read EXTRA_THREAD_ID, mark the thread read in the provider,
        // dismiss the notification.
    }
}
