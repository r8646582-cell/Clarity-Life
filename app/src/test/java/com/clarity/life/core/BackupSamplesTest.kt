package com.clarity.life.core

import com.clarity.life.core.Vectors.int
import com.clarity.life.core.Vectors.str
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every backup format and database version the app has written still opens (docs/MAX-PLAN.md, Phase 1, step 3).
 * Reads contracts/backups/backup_vectors.json and the sample files in contracts/backups/, the very same ones
 * tools/test_backup_format.py opens, and the emulator's RestoreTest restores. A change to [BackupFormat] that could
 * no longer open an old backup, or that opens a damaged one, fails here.
 */
class BackupSamplesTest {

    private val v = Vectors.load("contracts/backups/backup_vectors.json")
    private val pass get() = v.str("passphrase").toCharArray()
    private val minIterations = v.int("min_iterations")

    private fun file(name: String) = Vectors.repoFile("contracts/backups/$name").readBytes()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun read(bytes: ByteArray, passphrase: CharArray): Pair<BackupFormat.Header, ByteArray> {
        val (h, input) = BackupFormat.opening(java.io.ByteArrayInputStream(bytes), passphrase, minIterations)
        return h to input.use { it.readBytes() }
    }

    @Test
    fun everySampleOpensAndHoldsWhatItShould() {
        val samples = v.getValue("samples").jsonArray.map { it.jsonObject }
        assertTrue(samples.size >= 6)
        for (s in samples) {
            val name = s.str("file")
            val (h, json) = read(file(name), pass)
            assertEquals(name, s.int("format"), h.format)
            assertEquals(name, s.int("schema"), h.schema)
            assertEquals(name, s.getValue("created_at").jsonPrimitive.long, h.createdAt)
            assertEquals(name, s.int("iterations"), h.iterations)
            assertEquals("$name: the JSON inside", s.str("json_sha256"), sha256(json))
            // The header alone, as Settings reads it before the passphrase is typed.
            assertEquals(name, h.createdAt, BackupFormat.header(java.io.ByteArrayInputStream(file(name))).createdAt)
            val tables = Json.parseToJsonElement(json.toString(Charsets.UTF_8)).jsonObject.getValue("tables").jsonObject
            val rows = tables.mapValues { it.value.jsonArray.size }.filterValues { it > 0 }
            val want = s.getValue("rows").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
            assertEquals("$name: rows per table", want, rows)
        }
    }

    @Test
    fun everyDamagedCopyFailsWithThePlainReason() {
        for (case in v.getValue("damaged").jsonArray.map { it.jsonObject }) {
            val bytes = damage(file(case.str("file")), case)
            val passphrase = if (case.str("change") == "wrong_passphrase") "paper passphrase — kept ✗".toCharArray() else pass
            try {
                read(bytes, passphrase)
                fail("opened a damaged backup: $case")
            } catch (e: BackupFormat.BadBackup) {
                assertEquals("$case", case.str("why"), e.why)
            }
        }
    }

    /** The same changes tools/test_backup_format.py makes. */
    private fun damage(data: ByteArray, case: JsonObject): ByteArray {
        val nl = data.indexOf('\n'.code.toByte()) + 1
        val head = data.copyOfRange(0, nl)
        val body = data.copyOfRange(nl, data.size)
        return when (case.str("change")) {
            "flip_byte" -> head + body.clone().also { val at = case.int("at"); it[at] = (it[at].toInt() xor 1).toByte() }
            "cut_end" -> data.copyOfRange(0, data.size - case.int("bytes"))
            "drop_last_record" -> head + records(body).dropLast(1).fold(ByteArray(0)) { a, b -> a + b }
            "repeat_first_record" -> data + records(body).first()
            "body_only_header" -> head
            "header_replace" -> {
                val text = String(head, Charsets.UTF_8)
                require(case.str("from") in text) { "$case" }
                text.replaceFirst(case.str("from"), case.str("to")).toByteArray(Charsets.UTF_8) + body
            }
            "wrong_passphrase" -> data
            else -> error("unknown change $case")
        }
    }

    private fun records(body: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < body.size) {
            val n = java.nio.ByteBuffer.wrap(body, off, 4).int
            out.add(body.copyOfRange(off, off + 4 + n))
            off += 4 + n
        }
        return out
    }
}
