package dev.chuds.stillsms.data

/** Direction tag matching the plaintext export marker — `->` outbound, `<-` inbound. */
enum class Direction { Outbound, Inbound }

/** A single rendered message, fused from the sms or mms provider. */
data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val direction: Direction,
    val read: Boolean,
    val isMms: Boolean,
    /** Local content URI (file:// or content://) for an attached MMS image part, if any. */
    val attachmentUri: String? = null,
    /** True if outbound delivery hit a PDU/SMSC failure. The only status worth showing. */
    val failed: Boolean = false,
)

/** A conversation row for the thread list. */
data class Thread(
    val id: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val timestamp: Long,
    val read: Boolean,
    val messageCount: Int,
)
