package com.clarity.life.data

import com.clarity.life.core.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Room entities really are the schema in db/schema.sql: same tables, same columns in the same
 * order, same types, same NOT NULL, same defaults, same primary keys.
 *
 * It reads the newest schema Room exported at build time (app/schemas/.../N.json, committed) and the
 * hand-written db/schema.sql, and compares them. It also ties the CHECK (... IN (...)) lists of the schema
 * to [Values.ALLOWED], because Room cannot create CHECK constraints: the lists are enforced in Kotlin
 * instead, and this test is what stops the two copies from drifting.
 */
class SchemaTest {

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val primaryKey: Boolean,
        val checkValues: Set<String>?,
    )

    private data class Table(
        val name: String,
        val columns: List<Column>,
        val tableChecks: List<String>,
        val virtual: Boolean,
        val body: String,
    )

    private val sqlTables: Map<String, Table> by lazy { parseSchemaSql(Vectors.readText("db/schema.sql")) }

    private val roomEntities: Map<String, JsonObject> by lazy {
        // The newest exported version is the one the entities must match.
        val dir = Vectors.repoFile("app/schemas/com.clarity.life.data.ClarityDatabase")
        val file = dir.listFiles { f -> f.name.matches(Regex("\\d+\\.json")) }
            ?.maxByOrNull { it.name.removeSuffix(".json").toInt() }
            ?: error("no exported schema in ${dir.path}")
        Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
            .getValue("entities").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("tableName").jsonPrimitive.content }
    }

    @Test
    fun roomHasExactlyTheTablesOfTheSchema() {
        assertEquals(
            "the tables of db/schema.sql and of the Room database differ",
            sqlTables.keys.sorted(),
            roomEntities.keys.sorted(),
        )
        assertEquals("the spec says 34 tables", 34, sqlTables.size)
    }

    @Test
    fun everyColumnMatchesTheSchema() {
        val problems = mutableListOf<String>()
        for ((name, table) in sqlTables) {
            val entity = roomEntities[name] ?: continue
            val fields = entity.getValue("fields").jsonArray.map { it.jsonObject }
            val roomColumns = fields.map { it.getValue("columnName").jsonPrimitive.content }
                .filter { it != "rowid" }
            val sqlColumns = table.columns.map { it.name }
            if (roomColumns != sqlColumns) {
                problems.add("$name: columns $roomColumns, the schema says $sqlColumns")
                continue
            }
            if (table.virtual) continue // an FTS4 table declares no types in the schema
            for (column in table.columns) {
                val field = fields.first { it.getValue("columnName").jsonPrimitive.content == column.name }
                val affinity = field.getValue("affinity").jsonPrimitive.content
                if (affinity != column.type) {
                    problems.add("$name.${column.name}: Room says $affinity, the schema says ${column.type}")
                }
                val notNull = field["notNull"]?.jsonPrimitive?.booleanOrNull ?: false
                if (notNull != column.notNull) {
                    problems.add("$name.${column.name}: Room notNull=$notNull, the schema says ${column.notNull}")
                }
                val roomDefault = field["defaultValue"]?.jsonPrimitive?.content?.let { unquote(it) }
                if (roomDefault != column.default) {
                    problems.add(
                        "$name.${column.name}: Room default ${show(roomDefault)}, " +
                            "the schema says ${show(column.default)}",
                    )
                }
            }
            val roomPk = entity["primaryKey"]?.jsonObject?.getValue("columnNames")?.jsonArray
                ?.map { it.jsonPrimitive.content }?.filter { it != "rowid" } ?: emptyList()
            val sqlPk = primaryKeyColumns(table)
            if (roomPk.sorted() != sqlPk.sorted()) {
                problems.add("$name: Room primary key $roomPk, the schema says $sqlPk")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theCheckListsOfTheSchemaAreTheListsTheCodeEnforces() {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for ((tableName, table) in sqlTables) {
            for (column in table.columns) {
                val values = column.checkValues ?: continue
                val key = "$tableName.${column.name}"
                if (values == setOf("0", "1")) {
                    seen.add(key)
                    if (key !in Values.BOOLEAN_COLUMNS) {
                        problems.add("$key is CHECK (IN (0,1)) in the schema but is not in Values.BOOLEAN_COLUMNS")
                    }
                    continue
                }
                seen.add(key)
                val allowed = Values.ALLOWED[key]
                if (allowed == null) {
                    problems.add("$key has a CHECK list in the schema but none in Values.ALLOWED")
                } else if (allowed != values) {
                    problems.add("$key: Values.ALLOWED is ${allowed.sorted()}, the schema says ${values.sorted()}")
                }
            }
        }
        for (key in Values.ALLOWED.keys + Values.BOOLEAN_COLUMNS) {
            if (key !in seen) problems.add("$key is listed in Values but the schema has no CHECK list for it")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ---------- a small parser for the subset of SQL db/schema.sql uses ----------

    private fun unquote(s: String) = s.trim().removeSurrounding("'")

    private fun show(s: String?) = if (s == null) "none" else "\"$s\""

    private fun primaryKeyColumns(table: Table): List<String> {
        val inline = table.columns.filter { it.primaryKey }.map { it.name }
        if (inline.isNotEmpty()) return inline
        val m = Regex("PRIMARY KEY\\s*\\(([^)]*)\\)").find(table.body) ?: return emptyList()
        return m.groupValues[1].split(",").map { it.trim().trim('"') }
    }

    private fun parseSchemaSql(sql: String): Map<String, Table> {
        val clean = sql.lines().joinToString("\n") { line ->
            val at = line.indexOf("--")
            if (at >= 0) line.substring(0, at) else line
        }
        val tables = LinkedHashMap<String, Table>()
        val head = Regex("CREATE\\s+(VIRTUAL\\s+)?TABLE\\s+(\\w+)\\s*(USING\\s+fts4\\s*)?\\(", RegexOption.IGNORE_CASE)
        var index = 0
        while (true) {
            val m = head.find(clean, index) ?: break
            val virtual = m.groupValues[1].isNotBlank()
            val name = m.groupValues[2]
            var depth = 1
            var i = m.range.last + 1
            while (i < clean.length && depth > 0) {
                when (clean[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            val body = clean.substring(m.range.last + 1, i - 1)
            tables[name] = Table(name, parseColumns(body, virtual), tableChecks(body), virtual, body)
            index = i
        }
        return tables
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in body) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { parts.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun tableChecks(body: String): List<String> =
        splitTopLevel(body).filter { it.startsWith("CHECK", ignoreCase = true) }

    private fun parseColumns(body: String, virtual: Boolean): List<Column> {
        val out = mutableListOf<Column>()
        for (part in splitTopLevel(body)) {
            val upper = part.uppercase()
            if (upper.startsWith("CHECK") || upper.startsWith("UNIQUE") ||
                upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY")
            ) {
                continue
            }
            val words = part.split(Regex("\\s+"))
            val name = words[0].trim('"')
            if (virtual) {
                // fts4(kind, ref_id, title, body, tokenize=unicode61): no types, and the last item is an option
                if (name.contains("=")) continue
                out.add(Column(name, "TEXT", true, null, false, null))
                continue
            }
            val type = words.getOrNull(1)?.uppercase() ?: "TEXT"
            val primaryKey = upper.contains("PRIMARY KEY")
            val notNull = upper.contains("NOT NULL") || (primaryKey && type == "INTEGER")
            val default = Regex("DEFAULT\\s+('[^']*'|[^\\s,)]+)", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)?.let { unquote(it) }
            val check = Regex("CHECK\\s*\\(\\s*${Regex.escape(name)}\\s+IN\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().trim('\'') }?.toSet()
            out.add(Column(name, type, notNull, default, primaryKey, check))
        }
        return out
    }
}
