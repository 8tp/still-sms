package dev.chuds.stillsms.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * RESPOND_VIA_MESSAGE bound service. The dialer's "decline + reply" UI fires
 * Intent(ACTION_RESPOND_VIA_MESSAGE) at us with the recipient in the data URI
 * and the canned text in EXTRA_TEXT. We send it via SmsSender and stop.
 *
 * The system makes no callbacks here — there's nothing to bind to. We treat the
 * intent as one-shot.
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let(::handle)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handle(intent: Intent) {
        val data = intent.data ?: return
        // Same scheme-specific-part trick as ComposeActivity.
        val recipient = data.schemeSpecificPart?.split(',', '?')?.firstOrNull()?.trim()
            ?: return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra("android.intent.extra.TEXT")
            ?: return
        if (text.isBlank()) return
        SmsSender.send(applicationContext, recipient, text)
    }
}
