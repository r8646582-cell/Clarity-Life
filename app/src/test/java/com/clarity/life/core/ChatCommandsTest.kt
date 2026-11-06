package com.clarity.life.core

import com.clarity.life.core.Vectors.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/command_vectors.json, the same file tools/test_commands.py reads. */
class ChatCommandsTest {
    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/command_vectors.json")
        for (c in cases) {
            val text = (c.getValue("args").jsonObject.getValue("text") as JsonPrimitive).contentOrNull
            val got = Vectors.json(ChatCommands.match(text))
            if (!Vectors.same(got, c.getValue("expect"))) failures.add("${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 15)
    }
}
