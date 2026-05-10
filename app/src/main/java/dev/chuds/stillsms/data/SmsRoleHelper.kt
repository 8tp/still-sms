package dev.chuds.stillsms.data

/*
 * SmsRoleHelper — the only thing that knows how to ask the system to make us the
 * default SMS app.
 *
 * Two paths historically (still both valid):
 *   API 29+ : RoleManager.createRequestRoleIntent(ROLE_SMS) → user-facing system dialog.
 *             This is the modern, role-framework path. ROLE_SMS only appears as a request
 *             target if our manifest declares all four required entries (SENDTO activity,
 *             SMS_DELIVER receiver, WAP_PUSH_DELIVER receiver, RESPOND_VIA_MESSAGE service).
 *   pre-29  : Telephony.Sms.getDefaultSmsPackage(ctx) + Intent(ACTION_CHANGE_DEFAULT).
 *
 * minSdk is 26, so we keep both paths.
 */

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

object SmsRoleHelper {

    fun isDefault(context: Context): Boolean {
        // RoleManager.isRoleHeld is the modern source of truth; the legacy
        // Telephony.Sms.getDefaultSmsPackage() can lag on emulator and graphene builds
        // when the role was set via cmd role rather than the user-facing dialog.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleHeld(RoleManager.ROLE_SMS)) return true
        }
        val pkg = Telephony.Sms.getDefaultSmsPackage(context) ?: return false
        return pkg == context.packageName
    }

    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_SMS)
    }

    /** Returns the intent to fire (via startActivityForResult) to ask for the role.
     *  Caller is responsible for refreshing UI on result. */
    fun requestRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) return null
            return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
        }
        @Suppress("DEPRECATION")
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }
}
