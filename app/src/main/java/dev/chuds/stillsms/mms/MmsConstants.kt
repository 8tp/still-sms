package dev.chuds.stillsms.mms

/**
 * WSP / MMS encapsulation field codes — the subset still-sms uses.
 *
 * These come from OMA-WAP-MMS-ENC-V1_3 (WSP encoding) and OMA-MMS-ENC-V1_3
 * (MMS field assignments). The wire format is binary: each header is one
 * field-code byte (high bit set = "short header") followed by an encoded
 * value whose shape depends on the field type. We deliberately port only
 * the fields M-Send.req actually requires and the few we look at on the
 * inbound side; the rest live in the platform's hidden PduComposer / PduParser
 * which we are not allowed to depend on.
 */
internal object MmsField {
    const val MESSAGE_TYPE: Int = 0x8C
    const val TRANSACTION_ID: Int = 0x98
    const val MMS_VERSION: Int = 0x8D
    const val FROM: Int = 0x89
    const val TO: Int = 0x97
    const val SUBJECT: Int = 0x96
    const val CONTENT_TYPE: Int = 0x84
    const val CONTENT_LOCATION: Int = 0x83
    const val MESSAGE_ID: Int = 0x8B
    const val MESSAGE_CLASS: Int = 0x8A
    const val EXPIRY: Int = 0x88
    const val DELIVERY_REPORT: Int = 0x86
    const val READ_REPORT: Int = 0x90
    const val MESSAGE_SIZE: Int = 0x8E
}

internal object MmsMessageType {
    const val M_SEND_REQ: Int = 0x80
    const val M_SEND_CONF: Int = 0x81
    const val M_NOTIFICATION_IND: Int = 0x82
    const val M_RETRIEVE_CONF: Int = 0x84
    const val M_ACKNOWLEDGE_IND: Int = 0x85
}

/** MMS-Version field encodes 1.<minor> as 0x80 | (major<<4) | minor. v1.0 = 0x90. */
internal object MmsVersion {
    const val V1_0: Int = 0x90
    const val V1_3: Int = 0x93
}

/** "Insert-address-token" — From: header value when we want the carrier to fill in our number. */
internal const val MMS_FROM_INSERT_TOKEN: Int = 0x81

/**
 * WSP well-known content-type codes used when we encode Content-Type as a single byte.
 * Multipart variants ride a "long" content-type form built by hand in the encoder, since
 * MIME boundary parameters can't be expressed as a well-known short int.
 */
internal object WspContentType {
    const val APPLICATION_VND_WAP_MMS_MESSAGE: Int = 0x3E
    const val IMAGE_JPEG: Int = 0x1E
    const val IMAGE_PNG: Int = 0x20
    const val IMAGE_GIF: Int = 0x1D
    const val TEXT_PLAIN: Int = 0x03
}

/**
 * WSP well-known parameter codes used inside Content-Type.
 *   - 0x89 = "type" (the multipart/related root part's content-type)
 *   - 0x8A = "start" (the multipart/related root part's Content-ID)
 *   - 0x99 = "boundary"
 *   - 0x81 = "charset"
 *   - 0x83 = "name"
 */
internal object WspParam {
    const val TYPE: Int = 0x89
    const val START: Int = 0x8A
    const val BOUNDARY: Int = 0x99
    const val CHARSET: Int = 0x81
    const val NAME: Int = 0x83
}

/** UTF-8 charset = MIBenum 106 → encoded as long-int 0x81 0x6A in WSP. */
internal const val WSP_CHARSET_UTF8: Int = 0x6A
