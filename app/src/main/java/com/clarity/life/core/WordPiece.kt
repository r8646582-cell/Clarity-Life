package com.clarity.life.core

import java.text.Normalizer

/**
 * The tokenizer of the meaning model (mdbr-leaf-mt; bge-small-en-v1.5 before Max plan Phase 4, the same
 * 30,522 words): BERT uncased WordPiece, ported from the Hugging Face tokenizer
 * (BertNormalizer, BertPreTokenizer, WordPiece, then [CLS] ... [SEP], at most 512 ids). Pure Kotlin.
 *
 * TokenizerTest runs every case in contracts/embedding_vectors.json, which tools/make_embedding_vectors.py
 * made with the reference tokenizer, so a wrong id (and therefore a wrong vector) cannot slip through.
 * Everything is done per Unicode code point, the way the reference does it.
 */
class WordPiece(vocabLines: List<String>) {

    private val vocab: Map<String, Int> = HashMap<String, Int>().also { map ->
        vocabLines.forEachIndexed { i, line -> if (line.isNotEmpty()) map.putIfAbsent(line, i) }
    }

    private val unk = id("[UNK]")
    private val cls = id("[CLS]")
    private val sep = id("[SEP]")

    private fun id(token: String) = vocab[token] ?: throw IllegalArgumentException("the vocabulary has no $token")

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids.add(cls)
        for (word in preTokenize(normalize(text))) {
            for (piece in wordPiece(word)) {
                if (ids.size >= MAX_TOKENS - 1) break
                ids.add(piece)
            }
            if (ids.size >= MAX_TOKENS - 1) break
        }
        ids.add(sep)
        return ids.toIntArray()
    }

    // ---------- BertNormalizer: clean text, space around CJK, strip accents, lowercase ----------

    private fun normalize(text: String): String {
        val cleaned = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isChinese(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val stripped = StringBuilder()
        Normalizer.normalize(cleaned, Normalizer.Form.NFD).codePoints().forEach { cp ->
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) stripped.appendCodePoint(cp)
        }
        // Lowercase one code point at a time, like the reference: no final-sigma rule, and the one
        // unconditional multi-character mapping (capital I with dot above) written out.
        val lower = StringBuilder()
        stripped.codePoints().forEach { cp ->
            if (cp == 0x130) lower.append("i̇") else lower.appendCodePoint(Character.toLowerCase(cp))
        }
        return lower.toString()
    }

    /** Tab, newline and carriage return are whitespace; every other "other" (C*) character is dropped. */
    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(),
            -> true
            else -> false
        }
    }

    /** Rust's char::is_whitespace (the Unicode White_Space property). */
    private fun isWhitespace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> cp in 0x2000..0x200A
    }

    private fun isChinese(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
            cp in 0x2B740..0x2B81F || cp in 0x2B920..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    // ---------- BertPreTokenizer: split on whitespace, each punctuation mark on its own ----------

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }
    }

    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) { words.add(cur.toString()); cur.setLength(0) }
        }
        text.codePoints().forEach { cp ->
            when {
                isWhitespace(cp) -> flush()
                isPunctuation(cp) -> { flush(); words.add(String(Character.toChars(cp))) }
                else -> cur.appendCodePoint(cp)
            }
        }
        flush()
        return words
    }

    // ---------- WordPiece: greedy longest match, "##" for continuations ----------

    private fun wordPiece(word: String): List<Int> {
        val cps = word.codePoints().toArray()
        if (cps.size > MAX_CHARS_PER_WORD) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + String(cps, start, end - start)
                val hit = vocab[sub]
                if (hit != null) { found = hit; break }
                end--
            }
            if (found == null) return listOf(unk)
            out.add(found)
            start = end
        }
        return out
    }

    companion object {
        const val MAX_TOKENS = 512
        const val MAX_CHARS_PER_WORD = 100
    }
}
