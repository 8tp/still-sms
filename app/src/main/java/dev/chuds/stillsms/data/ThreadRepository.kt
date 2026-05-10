package dev.chuds.stillsms.data

/*
 * ThreadRepository — the only thing that touches the system mms-sms ContentProvider.
 *
 * Why we read straight from the system provider rather than maintaining a local mirror:
 *   The default-SMS role grants us read AND write to content://sms and content://mms
 *   without any extra permission, and the provider is the single source of truth that
 *   every other system surface (search, share targets, dialer "decline + reply") reads
 *   from. Mirroring would invite drift and reinvent backup for no gain.
 *
 * Why we observe via ContentObserver + a debounced re-query (vs. CursorAdapter, vs.
 * paging): the inbound volume is human-keyboard-paced, the on-screen list is one or two
 * dozen rows, and a re-query that costs ~5 ms is simpler than a paginated cache that
 * costs days of bug-hunting. If a 5,000-thread inbox ever becomes a real complaint, this
 * is the file to add Paging3 to — start by adding `pageSize` to the query signature.
 */

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OBSERVER_DEBOUNCE_MS = 100L

class ThreadRepository(
    private val context: Context,
    private val contactResolver: ContactNameResolver,
) {

    private val resolver = context.contentResolver

    /** All threads, newest-first. Updates whenever the mms-sms provider notifies a change. */
    fun observeThreads(): Flow<List<Thread>> = observeUri(Telephony.MmsSms.CONTENT_URI) {
        queryThreads()
    }

    /** Messages inside one thread, oldest-first. */
    fun observeMessages(threadId: Long): Flow<List<Message>> =
        observeUri(Telephony.MmsSms.CONTENT_URI) { queryMessages(threadId) }

    suspend fun threadIdForAddress(address: String): Long = withContext(Dispatchers.IO) {
        // Telephony.Threads.getOrCreateThreadId is the canonical resolver. It synthesizes a
        // thread_id for a never-before-seen address.
        runCatching {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }.getOrDefault(-1L)
    }

    suspend fun markThreadRead(threadId: Long): Unit = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        runCatching {
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }
        runCatching {
            resolver.update(
                Telephony.Mms.CONTENT_URI,
                values,
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }
    }

    suspend fun deleteThread(threadId: Long): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse("content://mms-sms/conversations/$threadId")
            resolver.delete(uri, null, null)
        }
    }

    suspend fun deleteMessage(messageId: Long, isMms: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val uri = if (isMms) {
                Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, messageId.toString())
            } else {
                Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId.toString())
            }
            runCatching { resolver.delete(uri, null, null) }
        }

    private fun <T> observeUri(uri: Uri, block: suspend () -> T): Flow<T> = callbackFlow {
        val ticks = Channel<Unit>(capacity = Channel.CONFLATED)
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                ticks.trySend(Unit)
            }
        }
        resolver.registerContentObserver(uri, true, observer)

        val scope = MainScope()
        var refreshJob: Job? = null

        fun refresh() {
            refreshJob?.cancel()
            refreshJob = scope.launch(Dispatchers.IO) {
                try {
                    val data = block()
                    if (isActive) trySend(data)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // swallow — provider hiccups shouldn't tear the flow down.
                }
            }
        }

        refresh()

        scope.launch {
            for (signal in ticks) {
                delay(OBSERVER_DEBOUNCE_MS)
                refresh()
            }
        }

        awaitClose {
            resolver.unregisterContentObserver(observer)
            scope.cancel()
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    private fun queryThreads(): List<Thread> {
        // content://mms-sms/conversations?simple=true returns one row per thread with
        // snippet + recipient_ids + date already joined across sms + mms.
        val uri = Telephony.Threads.CONTENT_URI.buildUpon()
            .appendQueryParameter("simple", "true")
            .build()
        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.RECIPIENT_IDS,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.DATE,
            Telephony.Threads.READ,
            Telephony.Threads.MESSAGE_COUNT,
        )
        val results = mutableListOf<Thread>()
        resolver.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val recipientIds = cursor.getString(1).orEmpty()
                val snippet = cursor.getString(2).orEmpty()
                val date = cursor.getLong(3)
                val read = cursor.getInt(4) == 1
                val count = cursor.getInt(5)
                val address = recipientAddress(recipientIds) ?: continue
                if (address.isBlank()) continue
                val info = contactResolver.lookup(address)
                results += Thread(
                    id = id,
                    address = address,
                    displayName = info.displayName,
                    photoUri = info.photoUri,
                    snippet = snippet,
                    timestamp = date,
                    read = read,
                    messageCount = count,
                )
            }
        }
        return results
    }

    private fun recipientAddress(recipientIdsCsv: String): String? {
        // Telephony.Threads.RECIPIENT_IDS is a space-separated list of canonical_addresses
        // _id values. We only render 1:1 threads in 0.1, so take the first id.
        val firstId = recipientIdsCsv.split(' ').firstOrNull { it.isNotBlank() } ?: return null
        val uri = Uri.parse("content://mms-sms/canonical-addresses")
        return resolver.query(uri, arrayOf("_id", "address"), "_id = ?", arrayOf(firstId), null)?.use {
            if (it.moveToFirst()) it.getString(1) else null
        }
    }

    private fun queryMessages(threadId: Long): List<Message> {
        // content://mms-sms/conversations/<threadId> — joined cursor over sms + mms with
        // a TRANSPORT_TYPE column distinguishing the two.
        val uri = Uri.parse("content://mms-sms/conversations/$threadId")
        val projection = arrayOf(
            "_id",
            "thread_id",
            "address",
            "body",
            "date",
            "type",
            "read",
            "transport_type",
            "ct_l",
            "sub_id",
            "status",
        )
        val results = mutableListOf<Message>()
        resolver.query(uri, projection, null, null, "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val transport = cursor.getString(7).orEmpty()
                if (transport == "mms") {
                    results += readMmsRow(cursor) ?: continue
                } else {
                    results += readSmsRow(cursor)
                }
            }
        }
        return results
    }

    private fun readSmsRow(cursor: android.database.Cursor): Message {
        val id = cursor.getLong(0)
        val threadId = cursor.getLong(1)
        val address = cursor.getString(2).orEmpty()
        val body = cursor.getString(3).orEmpty()
        val date = cursor.getLong(4)
        val type = cursor.getInt(5)
        val read = cursor.getInt(6) == 1
        val direction = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> Direction.Inbound
            else -> Direction.Outbound
        }
        val failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
        return Message(
            id = id,
            threadId = threadId,
            address = address,
            body = body,
            timestamp = date,
            direction = direction,
            read = read,
            isMms = false,
            failed = failed,
        )
    }

    private fun readMmsRow(cursor: android.database.Cursor): Message? {
        val id = cursor.getLong(0)
        val threadId = cursor.getLong(1)
        // MMS dates in the provider are seconds; SMS dates are millis. Normalize.
        val date = cursor.getLong(4) * 1000L
        val type = cursor.getInt(5)
        val read = cursor.getInt(6) == 1

        // MMS messageBox: 1=inbox, 4=outbox, 2=sent, 5=failed.
        val direction = when (type) {
            Telephony.Mms.MESSAGE_BOX_INBOX -> Direction.Inbound
            else -> Direction.Outbound
        }
        val failed = type == Telephony.Mms.MESSAGE_BOX_FAILED

        val text = mmsTextBody(id) ?: ""
        val attachment = mmsFirstImagePartUri(id)
        val address = mmsAddress(id, direction) ?: ""
        return Message(
            id = id,
            threadId = threadId,
            address = address,
            body = text,
            timestamp = date,
            direction = direction,
            read = read,
            isMms = true,
            attachmentUri = attachment,
            failed = failed,
        )
    }

    private fun mmsTextBody(mmsId: Long): String? {
        val partsUri = Uri.parse("content://mms/part")
        return resolver.query(
            partsUri,
            arrayOf("_id", "ct", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null,
        )?.use { cursor ->
            val sb = StringBuilder()
            while (cursor.moveToNext()) {
                val ct = cursor.getString(1).orEmpty()
                if (ct.startsWith("text/")) {
                    cursor.getString(2)?.let { if (sb.isNotEmpty()) sb.append('\n'); sb.append(it) }
                }
            }
            if (sb.isEmpty()) null else sb.toString()
        }
    }

    private fun mmsFirstImagePartUri(mmsId: Long): String? {
        val partsUri = Uri.parse("content://mms/part")
        return resolver.query(
            partsUri,
            arrayOf("_id", "ct"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val ct = cursor.getString(1).orEmpty()
                if (ct.startsWith("image/")) {
                    return@use Uri.withAppendedPath(partsUri, cursor.getString(0)).toString()
                }
            }
            null
        }
    }

    private fun mmsAddress(mmsId: Long, direction: Direction): String? {
        // content://mms/<id>/addr stores per-mms addresses; type=137 is FROM, 151 is TO.
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val typeWanted = if (direction == Direction.Inbound) 137 else 151
        return resolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == typeWanted) {
                    val a = cursor.getString(0)
                    if (!a.isNullOrBlank() && a != "insert-address-token") return@use a
                }
            }
            null
        }
    }
}
