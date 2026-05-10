package dev.chuds.stillsms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Entry point for `Intent(ACTION_SENDTO, "smsto:+15551234567")`. Required by the SMS
 * role; we resolve the recipient from the URI and bounce into MainActivity with an
 * extra so the app router lands on the right thread.
 *
 * 0.1 just opens the thread (or the empty thread for a never-seen number); 0.2 will
 * also pre-fill any `sms_body` extra into the composer.
 */
class ComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recipient = recipientFrom(intent?.data)
        val prefill = intent?.getStringExtra("sms_body").orEmpty()

        val forward = Intent(this, MainActivity::class.java).apply {
            action = ACTION_COMPOSE
            putExtra(EXTRA_RECIPIENT, recipient)
            putExtra(EXTRA_PREFILL_BODY, prefill)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(forward)
        finish()
    }

    companion object {
        const val ACTION_COMPOSE = "dev.chuds.stillsms.action.COMPOSE"
        const val EXTRA_RECIPIENT = "recipient"
        const val EXTRA_PREFILL_BODY = "prefill_body"

        private fun recipientFrom(data: Uri?): String? {
            if (data == null) return null
            // sms:, smsto:, mms:, mmsto: all carry the address in the scheme-specific part.
            val ssp = data.schemeSpecificPart ?: return null
            // "+15551234567" or "+15551234567,+15551234999" — take the first.
            return ssp.split(',', '?').firstOrNull()?.trim()?.ifBlank { null }
        }
    }
}
