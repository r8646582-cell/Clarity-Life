package com.clarity.life.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clarity.life.R
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.ChatChunk
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The reply runs here, in a foreground service, so it survives leaving the app and switching to another
 * one (CLAUDE.md, AI client rules). The partial text is written to the database about once a second.
 *
 * It is a `dataSync` service on purpose: Android stops a `shortService` after about three minutes, and a
 * reply with thinking and no length cap can legitimately run longer. [onTimeout] saves the partial reply as
 * interrupted. If the process is killed anyway, the startup repair sweep settles the row on next launch, so
 * every request ends in complete, interrupted or failed, and no spinner turns forever.
 */
@AndroidEntryPoint
class ReplyService : Service() {

    @Inject lateinit var chat: ChatRepository

    @Inject lateinit var bus: ReplyBus

    @Inject lateinit var reflections: com.clarity.life.memory.ReflectionScheduler

    // Anything that still slips through is written to the crash log and ends the reply, instead of
    // closing the whole app.
    private val guard = CoroutineExceptionHandler { _, e ->
        com.clarity.life.CrashLog.record(this, e)
        bus.fail("Something in the app went wrong (${e.javaClass.simpleName}). Details are in Settings, at the bottom.")
        val id = coachMessageId
        val kept = partial.toString()
        // The row must not stay streaming: Talk would show "thinking" for ever and hold Send. Its own scope,
        // because this service's scope is cancelled as the service stops.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                chat.settleIfStreaming(id, kept, AiFailure("unexpected", "the reply could not be finished", retryable = true), System.currentTimeMillis())
            } catch (e2: Exception) {
                com.clarity.life.CrashLog.record(this@ReplyService, e2)
            }
        }
        bus.end(id)
        stopSelf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)
    private var work: Job? = null
    private var coachMessageId: Long = 0
    private val partial = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val conversationId = intent?.getLongExtra(EXTRA_CONVERSATION, 0L) ?: 0L
        val messageId = intent?.getLongExtra(EXTRA_MESSAGE, 0L) ?: 0L
        if (conversationId == 0L || messageId == 0L) {
            if (work?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (work?.isActive == true && messageId == coachMessageId) return START_NOT_STICKY // the same request twice
        if (work?.isActive == true) {
            // One reply at a time: a second stream would share this reply's buffer and stop its service.
            scope.launch {
                chat.failCoachReply(messageId, AiFailure("busy", "another reply is being written", retryable = true), System.currentTimeMillis())
            }
            return START_NOT_STICKY
        }
        coachMessageId = messageId
        partial.setLength(0)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Some phones refuse a foreground service (battery rules). The reply still runs while the app is
            // open; only surviving an app switch is lost, and the log says why.
            com.clarity.life.CrashLog.record(this, e)
        }
        work = scope.launch { run(conversationId, messageId) }
        return START_NOT_STICKY
    }

    private suspend fun run(conversationId: Long, messageId: Long) {
        val now = System.currentTimeMillis()
        val chatType = chat.conversations.get(conversationId)?.chatType ?: "free"
        // Reading the key can throw (the Android Keystore fails now and then on some phones): that ends the
        // reply as failed, with Try again, like any other failure.
        val endpoint = try {
            chat.endpointForChat(chatType)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.clarity.life.CrashLog.record(this, e)
            chat.failCoachReply(messageId, AiFailure("no_key_read", "the API key could not be read", retryable = true), now)
            bus.fail("The API key could not be read. Try again; if it keeps failing, paste it again in Settings, Coach.")
            finish(messageId)
            return
        }
        if (endpoint == null) {
            chat.failCoachReply(
                messageId,
                AiFailure("no_key", "no API key is set yet", retryable = false),
                now,
            )
            bus.fail("Add your API key in Settings, Coach.")
            finish(messageId)
            return
        }
        bus.fail(null)
        bus.begin(messageId)
        var lastSave = 0L
        var windowMoved = false
        try {
            val packet = chat.buildPacket(conversationId, messageId, now)
            windowMoved = packet.windowMoved
            var finishReason: String? = null
            var usage: com.clarity.life.ai.Usage? = null
            chat.stream(endpoint, packet.request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Thinking -> bus.thinking(messageId)
                    is ChatChunk.Text -> {
                        partial.append(chunk.text)
                        bus.append(messageId, chunk.text)
                        val at = System.currentTimeMillis()
                        if (at - lastSave > SAVE_EVERY_MS) {
                            lastSave = at
                            chat.savePartial(messageId, partial.toString())
                        }
                    }

                    is ChatChunk.Finished -> {
                        finishReason = chunk.reason
                        usage = chunk.usage
                    }
                }
            }
            val chatType = chat.conversations.get(conversationId)?.chatType ?: "free"
            // The card step: a reply with no valid card is followed by a JSON-mode request for the cards it offered.
            val raw = chat.withCardFollowUp(
                endpoint = endpoint,
                request = packet.request,
                raw = partial.toString(),
                chatType = chatType,
                known = packet.known,
                finishReason = finishReason,
                now = System.currentTimeMillis(),
            )
            val outcome = chat.closeCoachReply(
                conversationId = conversationId,
                messageId = messageId,
                raw = raw,
                chatType = chatType,
                known = packet.known,
                finishReason = finishReason,
                usage = usage,
                now = System.currentTimeMillis(),
                modelLabel = endpoint.label,
            )
            // The coach said "tap below" and no button is there: say so, instead of leaving him to look for it.
            com.clarity.life.core.CardTalk.missingLine(outcome.prose, outcome.cardsOffered, outcome.cardsDropped)
                ?.let { bus.fail(it) }
        } catch (e: AiFailure) {
            if (partial.isNotEmpty()) {
                chat.interruptCoachReply(messageId, partial.toString(), System.currentTimeMillis())
            } else {
                chat.failCoachReply(messageId, e, System.currentTimeMillis())
            }
            bus.fail(com.clarity.life.ai.OpenAiCompatibleClient.friendly(e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The service is going away: keep what arrived, so the row never stays streaming.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                settleInterrupted(messageId)
            }
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a startup failure in a code class (ExceptionInInitializerError) is an
            // Error, and catching only Exception let one close the whole app on the first chat.
            com.clarity.life.CrashLog.record(this, e)
            bus.fail("Something in the app went wrong (${e.javaClass.simpleName}). Details are in Settings, at the bottom.")
            // Only a row still streaming is settled: a reply already stored as complete stays complete.
            chat.settleIfStreaming(
                messageId,
                partial.toString(),
                AiFailure("unexpected", "the reply could not be finished", retryable = true),
                System.currentTimeMillis(),
            )
        } finally {
            finish(messageId)
            // Every reply moves the reflection back to 30 minutes after it, unless the history window just
            // moved: then the summary behind EARLIER is brought up to date now. Scheduling never breaks a reply.
            try {
                if (windowMoved) reflections.reflectNow(conversationId) else reflections.afterReply(conversationId)
            } catch (e: Exception) {
                com.clarity.life.CrashLog.record(this, e)
            }
        }
    }

    /** Android is taking the service away: keep what arrived and mark it interrupted, with a Try again. */
    override fun onTimeout(startId: Int) {
        // Cancelling the reply settles its row (see the CancellationException branch of run).
        work?.cancel()
        stopSelf()
    }

    /** Android 15 and later call this one for a dataSync service that reached its daily limit. */
    override fun onTimeout(startId: Int, fgsType: Int) = onTimeout(startId)

    private suspend fun settleInterrupted(messageId: Long) {
        try {
            val row = chat.messages.get(messageId)
            if (row?.status == "streaming") {
                chat.interruptCoachReply(messageId, partial.toString(), System.currentTimeMillis())
            }
        } catch (e: Exception) {
            com.clarity.life.CrashLog.record(this, e)
        }
        bus.end(messageId)
    }

    private fun finish(messageId: Long) {
        bus.end(messageId)
        stopSelf()
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    description = "Shown only while a reply is being written, so it is never lost."
                },
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Writing a reply")
        .setSmallIcon(R.drawable.ic_reply_notification)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val CHANNEL = "reply"
        private const val NOTIFICATION_ID = 101
        private const val SAVE_EVERY_MS = 900L
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_MESSAGE = "message"

        /** The text is read from the database: an Intent is no place for his words, nor for a long paste. */
        fun start(context: Context, conversationId: Long, coachMessageId: Long) {
            val intent = Intent(context, ReplyService::class.java)
                .putExtra(EXTRA_CONVERSATION, conversationId)
                .putExtra(EXTRA_MESSAGE, coachMessageId)
            context.startForegroundService(intent)
        }
    }
}
