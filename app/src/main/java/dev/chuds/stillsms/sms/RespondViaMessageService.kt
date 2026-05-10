package dev.chuds.stillsms.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * RESPOND_VIA_MESSAGE bound service. Required by the default-SMS role: when the user
 * declines an incoming call with the system's "send a quick text" affordance, the
 * dialer fires Intent(ACTION_RESPOND_VIA_MESSAGE) at us. We never bind to it from
 * outside, but the manifest entry must be present.
 *
 * 0.2 will turn this into a real handler that sends the canned reply via SmsManager
 * and writes the row to the provider.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
