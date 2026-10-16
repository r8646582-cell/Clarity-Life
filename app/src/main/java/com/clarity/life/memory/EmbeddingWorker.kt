package com.clarity.life.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clarity.life.core.VectorMath
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.dao.ToEmbed
import com.clarity.life.data.entity.EmbeddingRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.SettingRow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gives every message of his, every conversation summary and every other record search reads its meaning vector,
 * in the background, on the phone. Re-running is harmless: it only looks at rows that have no vector yet.
 */
@Singleton
class EmbeddingIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val embedder: Embedder,
    private val search: SearchIndex,
) {

    /** Ask for the missing vectors to be made soon. Cheap to call after every message. */
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // A run that is going finishes, and one more follows for anything that arrived meanwhile.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            // Never on a low battery (Max plan Phase 5): after a model change it makes every vector again, which
            // is minutes to hours of work. Keyword search, and the vectors already made, work meanwhile.
            OneTimeWorkRequestBuilder<EmbeddingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** Settings, Advanced, Re-index: every vector is thrown away and made again. */
    suspend fun reindex() {
        db.embeddings().clear()
        schedule()
    }

    /**
     * Makes the missing vectors. Returns how many, or -1 when meaning search is off on this phone. After the
     * model changed (an app update), the old model's vectors are removed first and every vector is made again;
     * until then meaning search finds only what has its new vector, and keyword search carries on as usual.
     */
    suspend fun indexMissing(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.Default) {
        val dao = db.embeddings()
        if (db.settings().get(MODEL_KEY) != Embedder.MODEL) {
            // Once per model, not on every run: the delete reads the whole table.
            dao.deleteOtherModels(Embedder.MODEL)
            db.settings().put(SettingRow(MODEL_KEY, Embedder.MODEL))
        }
        var made = 0
        // A row the model cannot read is skipped for this run, so one bad row can never loop forever.
        val skipped = HashSet<String>()
        while (true) {
            val batch = dao.missingMessages(Embedder.MODEL, BATCH).map { SearchIndex.MESSAGE to it } +
                dao.missingSummaries(Embedder.MODEL, BATCH).map { SearchIndex.SUMMARY to it } +
                dao.missingQuotes(Embedder.MODEL, BATCH).map { SearchIndex.QUOTE to it } +
                dao.missingEvents(Embedder.MODEL, BATCH).map { SearchIndex.EVENT to ToEmbed(it.id, SearchIndex.eventText(it)) } +
                dao.missingRetiredNotes(Embedder.MODEL, BATCH).map { SearchIndex.NOTE to it } +
                dao.missingPromises(Embedder.MODEL, BATCH).map { SearchIndex.PROMISE to ToEmbed(it.id, SearchIndex.promiseText(it)) } +
                // Letters, reviews, chapters, goals, goal changes, targets and check-ins (Max plan Phase 2).
                search.recordsWithoutVectors(Embedder.MODEL, BATCH).map { it.kind to ToEmbed(it.id, it.text) }
            val todo = batch.filter { (kind, row) -> "$kind:${row.id}" !in skipped }
            if (todo.isEmpty()) break
            for ((kind, row) in todo) {
                val v = embedder.embed(row.text)
                if (v == null) {
                    val off = embedder.problem
                    db.diagnostics().log(ErrorLogRow(at = now, area = "search", code = off ?: "embed_failed", detail = ""))
                    if (off != null) return@withContext -1
                    skipped += "$kind:${row.id}"
                    continue
                }
                dao.put(EmbeddingRow(kind, row.id, Embedder.MODEL, Embedder.DIM, VectorMath.toBytes(v)))
                made++
            }
        }
        made
    }

    suspend fun count(): Int = db.embeddings().count(Embedder.MODEL)

    companion object {
        private const val WORK_NAME = "embedding-index"

        /** The model the stored vectors were last cleaned up for (derived-data bookkeeping, like the index version). */
        const val MODEL_KEY = "vectors_model"
        private const val BATCH = 50
    }
}

class EmbeddingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun indexer(): EmbeddingIndexer
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).indexer().indexMissing()
        // Meaning search being off is not a failure to retry: keyword search carries on.
        return Result.success()
    }
}
