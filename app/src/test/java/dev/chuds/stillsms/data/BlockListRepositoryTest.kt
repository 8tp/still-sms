package dev.chuds.stillsms.data

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListRepositoryTest {
    @Test
    fun addPreservesConcurrentWritesAcrossRepositoryInstances() = runBlocking {
        val filesRoot = Files.createTempDirectory("sms-block-list-test").toFile()
        try {
            val first = BlockListRepository(filesRoot)
            val second = BlockListRepository(filesRoot)

            coroutineScope {
                awaitAll(
                    async { first.add("+15550000001") },
                    async { second.add("BANK-ID") },
                )
            }

            val stored = filesRoot.resolve("blocked.json").readText()
            assertTrue(stored.contains("\"+15550000001\""))
            assertTrue(stored.contains("\"BANK-ID\""))
            assertEquals(1, Regex("\\+15550000001").findAll(stored).count())
            assertEquals(1, Regex("BANK-ID").findAll(stored).count())
        } finally {
            filesRoot.deleteRecursively()
        }
    }
}
