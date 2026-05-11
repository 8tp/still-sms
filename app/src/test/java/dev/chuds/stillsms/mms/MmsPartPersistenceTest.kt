package dev.chuds.stillsms.mms

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPartPersistenceTest {
    @Test
    fun returnsFirstTextSnippetAndWritesBinaryParts() {
        val sink = FakePartSink()

        val snippet = persistMmsParts(
            listOf(
                part("text/plain", "hello"),
                part("image/png", "png"),
            ),
            sink,
        )

        assertEquals("hello", snippet)
        assertEquals(emptyList<String>(), sink.deleted)
        assertEquals("png", sink.streams.getValue("part-2").toString(Charsets.UTF_8.name()))
    }

    @Test
    fun deletesInsertedPartsWhenBinaryStreamWriteFails() {
        val sink = FakePartSink(failOnOpen = "part-2")

        val error = runCatching {
            persistMmsParts(
                listOf(
                    part("text/plain", "hello"),
                    part("image/png", "png"),
                ),
                sink,
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(listOf("part-2", "part-1"), sink.deleted)
        assertEquals(emptyList<String>(), sink.inserted.keys.toList())
    }

    private fun part(contentType: String, body: String): MmsPduDecoder.RetrievePart =
        MmsPduDecoder.RetrievePart(
            contentType = contentType,
            contentId = null,
            contentLocation = null,
            name = null,
            data = body.toByteArray(),
        )

    private class FakePartSink(
        private val failOnOpen: String? = null,
    ) : MmsPartSink<String> {
        val inserted = linkedMapOf<String, MmsProviderPart>()
        val deleted = mutableListOf<String>()
        val streams = linkedMapOf<String, ByteArrayOutputStream>()

        override fun insert(part: MmsProviderPart): String {
            val id = "part-${inserted.size + deleted.size + 1}"
            inserted[id] = part
            return id
        }

        override fun openOutputStream(partId: String): OutputStream {
            if (partId == failOnOpen) throw IOException("stream failed")
            return ByteArrayOutputStream().also { streams[partId] = it }
        }

        override fun delete(partId: String) {
            deleted += partId
            inserted.remove(partId)
        }
    }
}
