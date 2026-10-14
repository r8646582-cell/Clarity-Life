package com.clarity.life.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ContextBuilder.trimBlocks] keeps running totals since Max plan Phase 5 instead of rendering and counting
 * the whole text after every drop. This compares it with the plain way (as tools/context.py does it) on random
 * blocks: digits, emoji, accents, text that needs escaping, items that may never drop, blocks that empty out.
 */
class TrimBlocksTest {

    private fun render(blocks: List<Block>, order: List<String>): String {
        val present = LinkedHashMap<String, Block>()
        for (b in blocks) if (b.items.isNotEmpty()) present[b.name] = b
        return order.filter { it in present }.joinToString("\n\n") { ContextBuilder.renderBlock(it, present.getValue(it).items) }
    }

    private fun plainTrim(blocks: List<Block>, order: List<String>, budget: Int): TrimResult {
        val names = blocks.map { it.name }
        val items = blocks.map { it.items.toMutableList() }
        fun snapshot() = names.indices.map { Block(names[it], items[it].toList()) }
        var dropped = 0
        while (ContextBuilder.estimateTokens(render(snapshot(), order)) > budget) {
            var bestBlock = -1
            var bestIndex = -1
            var bestRank = 0
            for (b in names.indices.sortedBy { order.indexOf(names[it]) }) {
                if (names[b] in ContextBuilder.NEVER_TRIM) continue
                for ((idx, item) in items[b].withIndex()) {
                    if (item.drop > 0 && item.drop > bestRank) {
                        bestBlock = b
                        bestIndex = idx
                        bestRank = item.drop
                    }
                }
            }
            if (bestBlock < 0) return TrimResult(snapshot(), dropped, true)
            items[bestBlock].removeAt(bestIndex)
            dropped++
        }
        return TrimResult(snapshot(), dropped, false)
    }

    private val pieces = listOf("plain words ", "12 Oct 2026 ", "café ", "😀 ", "[[promise]] ", "\n## NOW\n", "x", "7")

    private fun text(r: Random) = buildString { repeat(r.nextInt(0, 30)) { append(pieces[r.nextInt(pieces.size)]) } }

    @Test
    fun sameAsThePlainWay() {
        val r = Random(3)
        for (order in listOf(ContextBuilder.PART2_ORDER, ContextBuilder.PART3_ORDER)) {
            repeat(150) { n ->
                val names = order.shuffled(r).take(r.nextInt(1, order.size + 1))
                val blocks = names.map { name ->
                    Block(name, List(r.nextInt(0, 25)) { BlockItem(text(r), r.nextInt(0, 6)) })
                }
                val budget = r.nextInt(0, 1500)
                assertEquals("case $n", plainTrim(blocks, order, budget), ContextBuilder.trimBlocks(blocks, order, budget))
            }
        }
    }

    @Test
    fun aTenYearMemoryIsTrimmedTheSameWay() {
        val r = Random(9)
        val notes = Block("NOTES", List(400) { BlockItem("A note about ${text(r)}", 1 + it % 5) })
        val sessions = Block("RECENT_SESSIONS", List(30) { BlockItem(text(r), 2) })
        val blocks = listOf(Block("FLAGS", listOf(BlockItem("tough love: balanced", 0))), notes, sessions)
        assertEquals(
            plainTrim(blocks, ContextBuilder.PART2_ORDER, ContextBuilder.PART2_BUDGET),
            ContextBuilder.trimBlocks(blocks, ContextBuilder.PART2_ORDER, ContextBuilder.PART2_BUDGET),
        )
    }
}
