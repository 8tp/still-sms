package dev.chuds.stillsms.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

private const val MMS_ADDR_FROM = 137
private const val MMS_ADDR_CHARSET_UTF8 = 106

internal fun seedInboundMmsAddress(
    context: Context,
    mmsUri: Uri,
    from: String?,
): Long {
    if (from.isNullOrBlank()) return -1L

    val threadId = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, from)
    }.getOrDefault(-1L)
    if (threadId > 0) {
        context.contentResolver.update(
            mmsUri,
            ContentValues().apply { put(Telephony.Mms.THREAD_ID, threadId) },
            null,
            null,
        )
    }

    val mmsId = ContentUris.parseId(mmsUri)
    val addrUri = Uri.parse("content://mms/$mmsId/addr")
    runCatching {
        context.contentResolver.delete(
            addrUri,
            "type = ?",
            arrayOf(MMS_ADDR_FROM.toString()),
        )
    }
    context.contentResolver.insert(
        addrUri,
        ContentValues().apply {
            put("address", from)
            put("type", MMS_ADDR_FROM)
            put("charset", MMS_ADDR_CHARSET_UTF8)
            put("msg_id", mmsId)
        },
    )
    return threadId
}
