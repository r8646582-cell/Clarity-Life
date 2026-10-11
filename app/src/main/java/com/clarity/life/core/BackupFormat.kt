package com.clarity.life.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup file (spec section 9): AES-256-GCM over gzip(JSON), the key made from his passphrase with
 * PBKDF2-HMAC-SHA256 at 600,000 iterations. Pure (javax.crypto works the same on the JVM and on Android), so the
 * format is tested on the JVM, including a wrong passphrase and a changed byte.
 *
 * The file is one header line and then the ciphertext:
 *
 *     CLARITY-BACKUP {"format":1,"schema":2,"created_at":...,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,
 *                     "salt":"...","iv":"...","sha256":"..."}\n<ciphertext and GCM tag>
 *
 * The header says what is needed to open the file (iteration count, salt, nonce, schema version) and the
 * SHA-256 of the gzip bytes, which is checked after decryption ("read back and checksum-verified"). The header
 * is bound to the ciphertext as GCM associated data, so changing any of it makes the file fail to open.
 *
 * **Format 2** (what the app writes now) is the same idea in pieces, so a backup of years of chats never has
 * to sit in memory: after the header line comes a run of records, each a 4-byte length and then one chunk of
 * the gzip stream (at most [CHUNK] bytes) sealed with AES-256-GCM on its own. Chunk i uses the header's nonce
 * with i mixed into its last four bytes, and its associated data is the header line, i, and whether it is the
 * last chunk. A changed, missing, reordered or extra chunk, or a file cut short, fails to open. Format 2 has
 * no checksum in the header (it is not known when the header is written); the backup is checked by reading
 * it back and comparing the SHA-256 of what was written with what comes out.
 */
object BackupFormat {

    const val MAGIC = "CLARITY-BACKUP "
    const val FORMAT = 1
    const val FORMAT_STREAM = 2

    /** Plaintext bytes per sealed chunk in format 2. */
    const val CHUNK = 64 * 1024

    /** The longest header line a reader accepts before it decides the file is not a backup. */
    private const val MAX_HEADER = 4096
    const val ITERATIONS = 600_000

    /** Far above what this app writes; anything more is a broken header, not a stronger key. */
    const val MAX_ITERATIONS = 10_000_000
    const val KDF = "PBKDF2-HMAC-SHA256"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** A short passphrase protects nothing; the screen asks for at least this many characters. */
    const val MIN_PASSPHRASE = 8

    data class Header(
        val format: Int,
        val schema: Int,
        val createdAt: Long,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val sha256: String,
    )

    class BadBackup(val why: String) : Exception(why)

    /** Encrypts [json] (the whole database as text) into the bytes of one backup file. */
    fun seal(
        json: String,
        passphrase: CharArray,
        schema: Int,
        createdAt: Long,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val zipped = gzip(json.toByteArray(Charsets.UTF_8))
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = Header(FORMAT, schema, createdAt, iterations, salt, iv, sha256(zipped))
        val line = (MAGIC + headerJson(header)).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(line)
        val sealed = cipher.doFinal(zipped)
        return line + byteArrayOf('\n'.code.toByte()) + sealed
    }

    /** Reads only the header: what Settings shows for a file before he types the passphrase. */
    fun header(file: ByteArray): Header = split(file).first

    /** Reads only the header line from the start of a file, without reading the rest. */
    fun header(input: java.io.InputStream): Header = parseLine(readLine(input)).first

    /**
     * Format 2, writing: everything written to the returned stream is gzipped and sealed chunk by chunk into
     * [out]. Closing it writes the last chunk and closes [out]. The passphrase is used at once and not kept.
     */
    fun sealing(
        out: java.io.OutputStream,
        passphrase: CharArray,
        schema: Int,
        createdAt: Long,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): java.io.OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = Header(FORMAT_STREAM, schema, createdAt, iterations, salt, iv, "")
        val line = (MAGIC + headerJson(header)).toByteArray(Charsets.UTF_8)
        out.write(line)
        out.write('\n'.code)
        val sealer = ChunkSealer(out, key(passphrase, salt, iterations), iv, line)
        return GZIPOutputStream(sealer, CHUNK)
    }

    /**
     * Opens any backup as a stream of its JSON. Format 2 is read and checked chunk by chunk; a format 1 file
     * (made before streaming) is read whole, as it always was. The returned stream throws [BadBackup] the
     * moment something does not check out, so a reader must not commit anything until it reached the end.
     */
    fun opening(
        input: java.io.InputStream,
        passphrase: CharArray,
        minIterations: Int = 100_000,
    ): Pair<Header, java.io.InputStream> {
        val line = readLine(input)
        val (header, _) = parseLine(line)
        if (header.format != FORMAT && header.format != FORMAT_STREAM) {
            throw BadBackup("format ${header.format} is newer than this app")
        }
        if (header.format == FORMAT) {
            val rest = input.readBytes()
            val (h, json) = open(line + byteArrayOf('\n'.code.toByte()) + rest, passphrase, minIterations)
            return h to ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }
        if (header.iterations < minIterations) throw BadBackup("too few iterations")
        if (header.iterations > MAX_ITERATIONS) throw BadBackup("too many iterations")
        if (header.iv.size != IV_BYTES) throw BadBackup("the header is damaged")
        val opener = ChunkOpener(input, key(passphrase, header.salt, header.iterations), header.iv, line)
        val gz = try {
            GZIPInputStream(opener, CHUNK)
        } catch (e: java.io.IOException) {
            throw BadBackup("the contents are damaged")
        }
        return header to Unzipped(gz)
    }

    /** A damaged gzip stream inside chunks that all checked out is still a damaged backup. */
    private class Unzipped(private val inner: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int = try {
            inner.read()
        } catch (e: BadBackup) {
            throw e
        } catch (e: java.io.IOException) {
            throw BadBackup("the contents are damaged")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            inner.read(b, off, len)
        } catch (e: BadBackup) {
            throw e
        } catch (e: java.io.IOException) {
            throw BadBackup("the contents are damaged")
        }

        override fun close() = inner.close()
    }

    /** Format 2: buffers one chunk, and seals it once it knows whether more follows. */
    private class ChunkSealer(
        private val out: java.io.OutputStream,
        private val key: SecretKeySpec,
        private val iv: ByteArray,
        private val line: ByteArray,
    ) : java.io.OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var left = len
            while (left > 0) {
                // A full buffer is sealed only when more bytes arrive, so the last chunk is always known.
                if (filled == CHUNK) emit(last = false)
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n
                o += n
                left -= n
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            emit(last = true)
            closed = true
            out.close()
        }

        private fun emit(last: Boolean) {
            val sealed = chunkCipher(Cipher.ENCRYPT_MODE, key, iv, line, index, last).doFinal(buffer, 0, filled)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            index++
            filled = 0
        }
    }

    /** Format 2: reads one sealed chunk ahead, so it knows which one is the last. */
    private class ChunkOpener(
        private val input: java.io.InputStream,
        private val key: SecretKeySpec,
        private val iv: ByteArray,
        private val line: ByteArray,
    ) : java.io.InputStream() {
        private var next: ByteArray? = readRecord() ?: throw BadBackup("the file is cut short")
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == plain.size) {
                if (done) return -1
                val record = next ?: return -1
                next = readRecord()
                val last = next == null
                plain = try {
                    chunkCipher(Cipher.DECRYPT_MODE, key, iv, line, index, last).doFinal(record)
                } catch (e: AEADBadTagException) {
                    throw BadBackup("wrong passphrase, or the file was changed")
                }
                pos = 0
                index++
                if (last) done = true
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun readRecord(): ByteArray? {
            val size = ByteArray(4)
            val got = input.readNBytesCompat(size)
            if (got == 0) return null
            if (got < 4) throw BadBackup("the file is cut short")
            val length = java.nio.ByteBuffer.wrap(size).int
            if (length < TAG_BITS / 8 || length > CHUNK + TAG_BITS / 8) throw BadBackup("the file is damaged")
            val record = ByteArray(length)
            if (input.readNBytesCompat(record) < length) throw BadBackup("the file is cut short")
            return record
        }
    }

    /** Fills [into] as far as the stream allows; fewer bytes only at the end of the stream. */
    private fun java.io.InputStream.readNBytesCompat(into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun chunkCipher(mode: Int, key: SecretKeySpec, iv: ByteArray, line: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = iv.copyOf()
        for (i in 0 until 4) nonce[IV_BYTES - 1 - i] = (nonce[IV_BYTES - 1 - i].toInt() xor (index ushr (8 * i))).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(line)
        cipher.updateAAD(java.nio.ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array())
        return cipher
    }

    private fun readLine(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw BadBackup("not a Clarity backup")
            if (b == '\n'.code) break
            if (out.size() >= MAX_HEADER) throw BadBackup("not a Clarity backup")
            out.write(b)
        }
        return out.toByteArray()
    }

    /**
     * Decrypts a backup and checks it: the GCM tag (wrong passphrase or a changed byte fail here), then the
     * SHA-256 of the gzip bytes against the header, then the gzip itself. Returns the JSON. A header asking for
     * fewer than [minIterations] is refused, so a doctored file cannot make the key cheap to guess.
     */
    fun open(file: ByteArray, passphrase: CharArray, minIterations: Int = 100_000): Pair<Header, String> {
        val (header, line, sealed) = split(file).let { Triple(it.first, it.second, it.third) }
        if (header.format != FORMAT) throw BadBackup("format ${header.format} is newer than this app")
        if (header.iterations < minIterations) throw BadBackup("too few iterations")
        // A damaged or doctored header could otherwise hold the phone in key derivation for hours.
        if (header.iterations > MAX_ITERATIONS) throw BadBackup("too many iterations")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, header.salt, header.iterations), GCMParameterSpec(TAG_BITS, header.iv))
        cipher.updateAAD(line)
        val zipped = try {
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw BadBackup("wrong passphrase, or the file was changed")
        }
        if (sha256(zipped) != header.sha256) throw BadBackup("checksum does not match")
        val json = try {
            gunzip(zipped).toString(Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            throw BadBackup("the contents are damaged")
        }
        return header to json
    }

    /** "clarity-backup-2026-10-10-1930.cbk": sorted by name is sorted by time, which pruning relies on. */
    fun fileName(local: java.time.LocalDateTime): String =
        "clarity-backup-%04d-%02d-%02d-%02d%02d.cbk".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)

    fun isBackupName(name: String): Boolean = Regex("^clarity-backup-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.cbk$").matches(name)

    /** Which files to delete so only the newest [keep] stay. Never called before the new file was read back. */
    fun toPrune(names: List<String>, keep: Int = 4): List<String> =
        names.filter(::isBackupName).sortedDescending().drop(keep)

    private fun split(file: ByteArray): Triple<Header, ByteArray, ByteArray> {
        val nl = file.indexOf('\n'.code.toByte())
        if (nl < 0) throw BadBackup("not a Clarity backup")
        val line = file.copyOfRange(0, nl)
        return Triple(parseLine(line).first, line, file.copyOfRange(nl + 1, file.size))
    }

    private fun parseLine(line: ByteArray): Pair<Header, ByteArray> {
        val text = line.toString(Charsets.UTF_8)
        if (!text.startsWith(MAGIC)) throw BadBackup("not a Clarity backup")
        val header = try {
            parseHeader(text.removePrefix(MAGIC))
        } catch (e: BadBackup) {
            throw e
        } catch (e: Exception) {
            throw BadBackup("the header is damaged")
        }
        return header to line
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun headerJson(h: Header): String {
        val b64 = Base64.getEncoder()
        return "{\"format\":${h.format},\"schema\":${h.schema},\"created_at\":${h.createdAt}," +
            "\"kdf\":\"$KDF\",\"iterations\":${h.iterations}," +
            "\"salt\":\"${b64.encodeToString(h.salt)}\",\"iv\":\"${b64.encodeToString(h.iv)}\",\"sha256\":\"${h.sha256}\"}"
    }

    private fun parseHeader(json: String): Header {
        val obj = StrictJson.parse(json).asObj ?: throw BadBackup("the header is damaged")
        fun num(k: String) = obj.map[k]?.asNum ?: throw BadBackup("the header has no $k")
        fun str(k: String) = obj.map[k]?.asStr ?: throw BadBackup("the header has no $k")
        if (str("kdf") != KDF) throw BadBackup("unknown key derivation")
        val b64 = Base64.getDecoder()
        return Header(
            format = num("format").toInt(),
            schema = num("schema").toInt(),
            createdAt = num("created_at").toLong(),
            iterations = num("iterations").toInt(),
            salt = b64.decode(str("salt")),
            iv = b64.decode(str("iv")),
            sha256 = str("sha256"),
        )
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
