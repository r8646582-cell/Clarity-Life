package com.clarity.life.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export everything (spec section 9): one zip with a readable Markdown file (conversations, goals, plans,
 * check-ins, promises, notes with evidence, focuses, chapters) and the whole database as JSON. It is **not
 * encrypted**; Settings says so before he picks where it goes. Nothing is sent anywhere: he chooses the place.
 */
@Singleton
class Exporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
) {
    private val sqlite get() = db.openHelper.readableDatabase

    /** Writes the zip to [uri] (a file he created with the system's Save dialog). Returns a plain line. */
    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val markdown = markdown()
            val out = context.contentResolver.openOutputStream(uri, "w") ?: return@withContext "The file could not be written."
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("clarity-export.md"))
                zip.write(markdown.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("clarity-export.json"))
                // Streamed row by row into the zip, like a backup, so a large database never sits in memory.
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(zip, Charsets.UTF_8))
                backups.writeDump(writer, forExport = true)
                writer.flush()
                zip.closeEntry()
            }
            "Exported. This file is not encrypted: keep it somewhere private."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "The export could not be written (${e.javaClass.simpleName})."
        }
    }

    private suspend fun markdown(): String {
        val tz = settings.timeZone()
        fun at(ms: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
                .format(com.clarity.life.core.TimeFacts.local(Instant.ofEpochMilli(ms), tz))
        val sb = StringBuilder()
        sb.append("# Clarity export\n\nMade ${at(System.currentTimeMillis())}. Not encrypted.\n")

        sb.append("\n## Goals and plans\n")
        rows("SELECT id, title, area, deadline, status FROM goal ORDER BY id") { g ->
            sb.append("\n### G${g.long(0)} ${g.str(1)}\n\nArea ${g.str(2)}, status ${g.str(4)}")
            g.strOrNull(3)?.let { sb.append(", deadline $it") }
            sb.append("\n")
            rows("SELECT version, rationale, review_day, status FROM plan WHERE goal_id = ${g.long(0)} ORDER BY version") { p ->
                sb.append("\n- Plan v${p.long(0)} (${p.str(3)}, review ${p.str(2)}): ${p.str(1)}\n")
            }
            rows(
                "SELECT id, name, unit, minimum, target, schedule, status FROM target WHERE goal_id = ${g.long(0)} ORDER BY id",
            ) { t ->
                sb.append("- Target T${t.long(0)} ${t.str(1)}: ${n(t.double(4))} ${t.str(2)} (minimum ${n(t.double(3))}), ${t.str(5)}, ${t.str(6)}\n")
            }
            rows("SELECT id, name, total, unit, start_done, deadline, status FROM backlog WHERE goal_id = ${g.long(0)} ORDER BY id") { b ->
                sb.append("- Backlog B${b.long(0)} ${b.str(1)}: ${n(b.double(2))} ${b.str(3)} by ${b.str(5)} (${n(b.double(4))} done at the start), ${b.str(6)}\n")
            }
        }

        sb.append("\n## Check-ins\n\n")
        rows(
            "SELECT c.day_key, t.name, c.level, c.amount, t.unit, c.reason, c.note FROM checkin c " +
                "JOIN target t ON t.id = c.target_id ORDER BY c.day_key, t.id",
        ) { c ->
            sb.append("- ${c.str(0)} ${c.str(1)}: ${c.str(2)}, ${n(c.double(3))} ${c.str(4)}".trimEnd())
            c.strOrNull(5)?.let { sb.append(", reason: $it") }
            c.strOrNull(6)?.let { sb.append(", \"$it\"") }
            sb.append("\n")
        }

        sb.append("\n## Promises\n\n")
        rows("SELECT text, due_text, status, reason FROM promise ORDER BY due_at") { p ->
            sb.append("- ${p.str(0)} (${p.str(1)}): ${p.str(2)}")
            p.strOrNull(3)?.let { sb.append(", reason: $it") }
            sb.append("\n")
        }

        sb.append("\n## What I know\n")
        rows("SELECT key, value, confirmed FROM profile_entry ORDER BY key") { p ->
            sb.append("\n- ${p.str(0)}: ${p.str(1)}${if (p.long(2) == 1L) " (confirmed)" else ""}")
        }
        sb.append("\n")
        rows("SELECT id, kind, text, confidence, status FROM note WHERE status != 'deleted_by_user' ORDER BY kind, id") { note ->
            sb.append("\n- ${note.str(1)}, ${note.str(3)}${if (note.str(4) == "retired") ", archived" else ""}: ${note.str(2)}\n")
            rows("SELECT kind, text, day FROM note_evidence WHERE note_id = ${note.long(0)} ORDER BY day") { e ->
                sb.append("  - ${e.str(2)}: ${if (e.str(0) == "quote") "you said \"${e.str(1)}\"" else e.str(1)}\n")
            }
        }
        rows("SELECT name, relation, notes FROM person ORDER BY name") { p ->
            sb.append("\n- ${p.str(0)} (${p.str(1)}): ${p.str(2)}")
        }
        sb.append("\n")

        sb.append("\n## Focuses\n")
        rows("SELECT name, why, status, summary FROM focus ORDER BY id") { f ->
            sb.append("\n### ${f.str(0)} (${f.str(2)})\n\nWhy: ${f.str(1)}\n\n${f.str(3)}\n")
        }

        sb.append("\n## Chapters\n")
        rows("SELECT period_start, period_end, title, body FROM chapter ORDER BY period_start") { c ->
            sb.append("\n### ${c.str(2)} (${c.str(0)} to ${c.str(1)})\n\n${c.str(3)}\n")
        }

        sb.append("\n## Conversations\n")
        rows("SELECT id, title, started_at, summary FROM conversation WHERE off_the_record = 0 ORDER BY started_at") { c ->
            sb.append("\n### ${c.str(1).ifBlank { "Untitled" }} (${at(c.long(2))})\n")
            if (c.str(3).isNotBlank()) sb.append("\nSummary: ${c.str(3)}\n")
            rows("SELECT role, content, created_at FROM message WHERE conversation_id = ${c.long(0)} AND content != '' ORDER BY id") { m ->
                val who = if (m.str(0) == "user") "You" else "Coach"
                sb.append("\n**$who**, ${at(m.long(2))}:\n\n${m.str(1)}\n")
            }
        }
        return sb.toString()
    }

    private inline fun rows(sql: String, each: (Cursor) -> Unit) {
        sqlite.query(sql).use { c -> while (c.moveToNext()) each(c) }
    }

    private fun Cursor.str(i: Int): String = getString(i).orEmpty()

    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)?.takeIf { it.isNotBlank() }

    private fun Cursor.long(i: Int): Long = getLong(i)

    private fun Cursor.double(i: Int): Double = getDouble(i)

    /** 2.0 is written 2, 1.5 stays 1.5. */
    private fun n(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
}
