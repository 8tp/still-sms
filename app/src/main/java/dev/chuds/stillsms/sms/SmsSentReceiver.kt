package dev.chuds.stillsms.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * sentIntent landing pad. resultCode == Activity.RESULT_OK means the part left the modem
 * cleanly; anything else is one of the SmsManager.RESULT_ERROR_* codes and we mark the
 * whole message FAILED.
 *
 * For multi-part messages we wait until the LAST part reports back. If any single part
 * was a hard failure we mark FAILED; otherwise SENT.
 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val uriString = intent.getStringExtra(SmsSender.EXTRA_MESSAGE_URI) ?: return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val partIndex = intent.getIntExtra(SmsSender.EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(SmsSender.EXTRA_PART_COUNT, 1)

        val ok = resultCode == Activity.RESULT_OK
        val tracker = SendTracker.note(uri, partIndex, ok)
        // Only flip the provider row when every part has reported. Avoids a transient
        // SENT → FAILED flip mid-burst on bad-second-segment carriers.
        if (tracker.completed(partCount)) {
            if (tracker.anyFailed) SmsSender.markFailed(context, uri)
            else SmsSender.markSent(context, uri)
            SendTracker.clear(uri)
        }
    }

    /**
     * Per-message bookkeeping for the multi-part case. Keyed by URI, lives only as long
     * as the message is in flight. Because the receiver lives in the manifest, the
     * process may be cold-started for a sentIntent — but the broadcast for ALL parts of
     * one send arrives in the same process burst, so a JVM-static map is sufficient.
     */
    private object SendTracker {
        private val state = HashMap<Uri, MutableMap<Int, Boolean>>()

        @Synchronized
        fun note(uri: Uri, part: Int, ok: Boolean): Snapshot {
            val map = state.getOrPut(uri) { HashMap() }
            map[part] = ok
            return Snapshot(map.size, map.values.any { !it })
        }

        @Synchronized
        fun clear(uri: Uri) {
            state.remove(uri)
        }
    }

    private data class Snapshot(val seen: Int, val anyFailed: Boolean) {
        fun completed(total: Int): Boolean = seen >= total
    }

    companion object {
        const val ACTION_SENT = "dev.chuds.stillsms.action.SMS_SENT"
    }
}
