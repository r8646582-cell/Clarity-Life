package com.clarity.life.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.clarity.life.core.VectorMath
import com.clarity.life.core.WordPiece
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Meaning search's model, on the phone: Alibaba's gte-base-en-v1.5 (its own int8 ONNX export, 147 MB, bundled in
 * the APK, Apache-2.0 licence), run with ONNX Runtime. The text never leaves the phone. It replaced
 * bge-small-en-v1.5 in Max plan Phase 4: of 31 models and 8 mixes measured (tools/eval/compare_embedders.py), it
 * brought the most of what the coach needs within reach; the owner chose outcomes over size.
 *
 * The vector is the first token's ([CLS]) last hidden state, [DIM] numbers, L2-normalised, as the model card
 * says. The model takes no token_type_ids. No query prefix: his messages are compared with his messages and notes.
 *
 * Checked before use (spec section 7): on first load it embeds the reference sentence of
 * contracts/embedding_vectors.json and compares the result with the reference vector. If they differ, or
 * the model cannot load, meaning search stays off and retrieval uses keywords only; nothing else breaks.
 */
@Singleton
class Embedder @Inject constructor(@ApplicationContext private val context: Context) {

    private class Loaded(val env: OrtEnvironment, val session: OrtSession, val tokenizer: WordPiece)

    @Volatile private var loaded: Loaded? = null

    @Volatile private var failed: String? = null

    /** Why meaning search is off, as a content-free code, or null while it works (or was never tried). */
    val problem: String? get() = failed

    /** A unit vector of [DIM] floats, or null when meaning search is off. Blocking; call it off the main thread. */
    fun embed(text: String): FloatArray? {
        val l = load() ?: return null
        return try {
            com.clarity.life.core.PhoneTimings.timed(com.clarity.life.core.PhoneTimings.EMBED) { run(l, text) }
        } catch (e: Exception) {
            null
        }
    }

    private fun run(l: Loaded, text: String): FloatArray {
        val ids = l.tokenizer.encode(text)
        val n = ids.size.toLong()
        val shape = longArrayOf(1, n)
        val input = LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() })
        val mask = LongBuffer.wrap(LongArray(ids.size) { 1L })
        OnnxTensor.createTensor(l.env, input, shape).use { tIds ->
            OnnxTensor.createTensor(l.env, mask, shape).use { tMask ->
                l.session.run(mapOf("input_ids" to tIds, "attention_mask" to tMask), setOf(OUTPUT)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = out.get(0).value as Array<Array<FloatArray>>
                    val cls = hidden[0][0]
                    require(cls.size == DIM) { "unexpected vector size" }
                    return VectorMath.normalize(cls)
                }
            }
        }
    }

    @Synchronized
    private fun load(): Loaded? {
        loaded?.let { return it }
        if (failed != null) return null
        return try {
            val model = modelFile()
            val vocab = context.assets.open("$DIR/vocab.txt").bufferedReader().readLines()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = env.createSession(model.absolutePath, options)
            val l = Loaded(env, session, WordPiece(vocab))
            verify(l)
            loaded = l
            l
        } catch (e: Throwable) {
            failed = if (e is Mismatch) "embedder_mismatch" else "embedder_" + e.javaClass.simpleName
            null
        }
    }

    private class Mismatch : Exception("the embedder does not match its reference vector")

    /** The reference check: cosine at least [MIN_REFERENCE_COSINE] with the vector the reference made. */
    private fun verify(l: Loaded) {
        val ref = Json.parseToJsonElement(
            context.assets.open("contracts/embedding_vectors.json").bufferedReader().readText(),
        ).jsonObject.getValue("embeddings").jsonArray.first().jsonObject
        val text = ref.getValue("text").jsonPrimitive.content
        val expected = FloatArray(DIM) { ref.getValue("vector").jsonArray[it].jsonPrimitive.float }
        if (VectorMath.dot(run(l, text), expected) < MIN_REFERENCE_COSINE) throw Mismatch()
    }

    /**
     * ONNX Runtime reads the model from a file, so it is copied out of the APK once (to storage that is
     * never backed up) and reused. The APK holds it in [PARTS] parts (GitHub refuses one file over 100 MB),
     * joined here in order. A copy of the wrong size is replaced; the file of an earlier model is removed.
     */
    private fun modelFile(): File {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        for (old in OLD_MODEL_FILES) File(dir, old).delete()
        val file = File(dir, MODEL_FILE)
        if (file.exists() && file.length() == MODEL_BYTES) return file
        val tmp = File(dir, "$MODEL_FILE.part")
        tmp.outputStream().use { output ->
            for (i in 1..PARTS) context.assets.open("$DIR/model_quantized.onnx.part$i").use { it.copyTo(output) }
        }
        if (tmp.length() != MODEL_BYTES) {
            tmp.delete()
            throw IllegalStateException("the bundled model has the wrong size")
        }
        if (!tmp.renameTo(file)) throw IllegalStateException("the model could not be put in place")
        return file
    }

    companion object {
        /**
         * The name stored in embedding_row.model. A different model means its vectors are made again: rows of
         * any other model are removed and the missing ones made in the background (EmbeddingIndexer).
         */
        const val MODEL = "gte-base-en-v1.5-int8"
        const val DIM = 768
        const val MIN_REFERENCE_COSINE = 0.99
        private const val OUTPUT = "last_hidden_state"
        private const val DIR = "models/gte-base-en-v1.5"
        private const val MODEL_FILE = "gte-base-en-v1.5-int8.onnx"
        private const val PARTS = 2
        private const val MODEL_BYTES = 146_540_971L

        /** Files of earlier models, removed from the phone's storage when found. */
        private val OLD_MODEL_FILES = listOf("bge-small-en-v1.5-int8.onnx", "mdbr-leaf-mt-int8.onnx")
    }
}
