package com.clarity.life.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {

    // A low count keeps the test fast; the header carries the count, so opening uses whatever was written.
    private val fast = 1_000
    private val json = """{"schema":2,"tables":{"note":[{"id":1,"text":"Walks help — always ✓"}]}}"""

    private fun sealed() = BackupFormat.seal(json, "paper passphrase".toCharArray(), schema = 2, createdAt = 1_791_600_000_000L, iterations = fast)

    @Test
    fun whatGoesInComesOut() {
        val file = sealed()
        val (header, back) = BackupFormat.open(file, "paper passphrase".toCharArray(), minIterations = 1)
        assertEquals(json, back)
        assertEquals(2, header.schema)
        assertEquals(fast, header.iterations)
        assertEquals(1_791_600_000_000L, header.createdAt)
        assertEquals(header.sha256, BackupFormat.header(file).sha256)
        assertTrue(String(file.copyOfRange(0, 15), Charsets.UTF_8) == BackupFormat.MAGIC)
    }

    @Test
    fun theRealCountIsTheDefaultAndACheapHeaderIsRefused() {
        assertEquals(600_000, BackupFormat.ITERATIONS)
        expectBad { BackupFormat.open(sealed(), "paper passphrase".toCharArray()) }
    }

    @Test
    fun aWrongPassphraseFails() {
        expectBad { BackupFormat.open(sealed(), "paper passphrasE".toCharArray(), minIterations = 1) }
    }

    @Test
    fun aChangedByteAnywhereFails() {
        val file = sealed()
        // A byte of the ciphertext, and a field of the header (the header is bound to the data).
        val body = file.clone().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        expectBad { BackupFormat.open(body, "paper passphrase".toCharArray(), minIterations = 1) }
        val text = String(file, Charsets.ISO_8859_1).replace("\"created_at\":1791600000000", "\"created_at\":1791600000001")
        expectBad { BackupFormat.open(text.toByteArray(Charsets.ISO_8859_1), "paper passphrase".toCharArray(), minIterations = 1) }
    }

    @Test
    fun somethingElseIsNotABackup() {
        expectBad { BackupFormat.open("hello\nworld".toByteArray(), "x".toCharArray()) }
        expectBad { BackupFormat.open("no newline".toByteArray(), "x".toCharArray()) }
    }

    @Test
    fun namesSortByTimeAndOnlyTheNewestFourStay() {
        assertEquals("clarity-backup-2026-10-04-0905.cbk", BackupFormat.fileName(LocalDateTime.of(2026, 10, 4, 9, 5)))
        val names = listOf(
            "clarity-backup-2026-09-06-1930.cbk",
            "clarity-backup-2026-10-04-1930.cbk",
            "notes.txt",
            "clarity-backup-2026-09-13-1930.cbk",
            "clarity-backup-2026-09-27-1930.cbk",
            "clarity-backup-2026-09-20-1930.cbk",
        )
        assertEquals(listOf("clarity-backup-2026-09-06-1930.cbk"), BackupFormat.toPrune(names))
        assertEquals(emptyList<String>(), BackupFormat.toPrune(names.take(3)))
    }

    // ---------- format 2: sealed in chunks, read as a stream ----------

    /** Big enough for several chunks, and not a whole number of them. */
    private val big = buildString {
        append("{\"tables\":{\"message\":[")
        for (i in 0 until 40_000) append("{\"id\":$i,\"content\":\"line $i — ✓ ${i * 7919 % 104729}\"},")
        append("{}]}}")
    }

    private fun stream(text: String = big, pass: String = "paper passphrase"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        BackupFormat.sealing(out, pass.toCharArray(), schema = 3, createdAt = 1_791_600_000_000L, iterations = fast)
            .use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun read(file: ByteArray, pass: String = "paper passphrase"): Pair<BackupFormat.Header, String> {
        val (h, input) = BackupFormat.opening(java.io.ByteArrayInputStream(file), pass.toCharArray(), minIterations = 1)
        return h to input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun aStreamedBackupComesBackWholeInAnyLength() {
        assertTrue("the test needs several chunks", stream().size > 4 * BackupFormat.CHUNK)
        val (header, back) = read(stream())
        assertEquals(big, back)
        assertEquals(BackupFormat.FORMAT_STREAM, header.format)
        assertEquals(3, header.schema)
        assertEquals(1_791_600_000_000L, BackupFormat.header(java.io.ByteArrayInputStream(stream())).createdAt)
        assertEquals("", read(stream("")).second)
        assertEquals("x", read(stream("x")).second)
    }

    @Test
    fun anOldFormatOneBackupStillOpensAsAStream() {
        val (header, back) = read(sealed())
        assertEquals(BackupFormat.FORMAT, header.format)
        assertEquals(json, back)
    }

    @Test
    fun aStreamedBackupWithAWrongPassphraseFails() {
        expectBad { read(stream(), "paper passphrasE") }
    }

    @Test
    fun aStreamedBackupThatWasChangedCutOrExtendedFails() {
        val file = stream()
        val headerEnd = file.indexOf('\n'.code.toByte()) + 1
        // A byte in the middle, the last byte, the header, a file cut short, and a chunk added at the end.
        expectBad { read(file.clone().also { it[headerEnd + 5000] = (it[headerEnd + 5000] + 1).toByte() }) }
        expectBad { read(file.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) }
        val text = String(file, Charsets.ISO_8859_1).replaceFirst("\"schema\":3", "\"schema\":4")
        expectBad { read(text.toByteArray(Charsets.ISO_8859_1)) }
        expectBad { read(file.copyOfRange(0, file.size - 100)) }
        expectBad { read(file.copyOfRange(0, headerEnd)) }
        val firstRecord = file.copyOfRange(headerEnd, headerEnd + 4 + java.nio.ByteBuffer.wrap(file, headerEnd, 4).int)
        expectBad { read(file + firstRecord) }
        // The chunks of two backups made with the same passphrase cannot be mixed.
        val other = stream()
        expectBad { read(file.copyOfRange(0, headerEnd) + other.copyOfRange(other.indexOf('\n'.code.toByte()) + 1, other.size)) }
    }

    @Test
    fun aStreamedBackupNeedsTheRealIterationCount() {
        expectBad { BackupFormat.opening(java.io.ByteArrayInputStream(stream()), "paper passphrase".toCharArray()) }
    }

    private fun expectBad(block: () -> Unit) {
        try {
            block()
            fail("expected BadBackup")
        } catch (e: BackupFormat.BadBackup) {
            // expected
        }
    }
}
