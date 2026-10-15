package com.clarity.life.settings

import com.clarity.life.ai.Endpoint
import com.clarity.life.core.Thinking
import com.clarity.life.data.dao.SettingsDao
import com.clarity.life.data.entity.SettingRow
import com.clarity.life.security.SecretStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Every setting the owner can change, with the defaults from [DefaultSettings]. Plain preferences live in the
 * `settings` table; the API keys live only in the Keystore ([SecretStore]).
 *
 * Nothing here ever names a model at a call site: a call site asks for [chatEndpoint] and gets whatever he
 * typed in Settings, so a retired model never needs a new APK.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingsDao,
    private val secrets: SecretStore,
) {

    object Keys {
        const val PROVIDER = "provider"
        const val BASE_URL = "base_url"
        const val CHAT_MODEL = "chat_model"
        const val BACKGROUND_PROVIDER = "background_provider"
        const val BACKGROUND_BASE_URL = "background_base_url"
        const val BACKGROUND_MODEL = "background_model"
        const val CHAT_THINKING = "chat_thinking"
        const val CHAT_THINKING_FREE = "chat_thinking_free"
        const val CHAT_THINKING_UNTANGLE = "chat_thinking_untangle"
        const val CHAT_THINKING_DECIDE = "chat_thinking_decide"
        const val CHAT_THINKING_PLAN = "chat_thinking_plan"
        const val LETTER_MODEL = "letter_model"
        const val LETTER_THINKING = "letter_thinking"
        const val BACKUP_FOLDER = "backup_folder"
        const val APP_LOCK_ON = "app_lock_on"
        const val HIDE_IN_RECENTS_ON = "hide_in_recents_on"
        const val LAST_BACKUP_AT = "last_backup_at"
        const val LAST_BACKUP_LINE = "last_backup_line"
        const val BACKUP_EVERY_DAYS = "backup_every_days"
        const val BACKUP_KEEP = "backup_keep"
        /** The file of a backup being written, until it is verified: removed if the app dies half way. */
        const val BACKUP_WRITING = "backup_writing"
        const val MAINTENANCE_AT = "maintenance_at"
        const val MAINTENANCE_LINE = "maintenance_line"
        const val MAINTENANCE_DB_OK = "maintenance_db_ok"
        const val MAX_OUTPUT_TOKENS = "max_output_tokens"
        const val DAY_BOUNDARY_HOUR = "day_boundary_hour"
        const val TIME_ZONE = "time_zone"
        const val USER_NAME = "user_name"
        const val WEEKLY_LETTER_ON = "weekly_letter_on"
        const val SUNDAY_ALERT_ON = "sunday_alert_on"
        const val TOUGH_LOVE = "tough_love"
        const val LISTEN_ONLY = "listen_only"

        /** Max plan Phase 6: his minutes for planning, Monday to Sunday ([com.clarity.life.core.PlanLoad.parseHours]). */
        const val PLAN_HOURS = "plan_hours"
    }

    suspend fun get(key: String, fallback: String): String = dao.get(key) ?: fallback

    suspend fun set(key: String, value: String) = dao.put(SettingRow(key, value))

    suspend fun getInt(key: String, fallback: Int): Int = dao.get(key)?.toIntOrNull() ?: fallback

    suspend fun getBool(key: String, fallback: Boolean): Boolean =
        dao.get(key)?.let { it == "true" } ?: fallback

    suspend fun setBool(key: String, value: Boolean) = set(key, value.toString())

    /** The name as typed in Settings; empty when none is set. */
    suspend fun userName(): String = get(Keys.USER_NAME, DefaultSettings.USER_NAME).trim()

    /** The name the prompts use: the typed one, or a neutral "this person" until one is set. */
    suspend fun promptName(): String = userName().ifEmpty { DefaultSettings.UNNAMED }

    suspend fun dayBoundaryHour(): Int = getInt(Keys.DAY_BOUNDARY_HOUR, DefaultSettings.DAY_BOUNDARY_HOUR)

    suspend fun timeZone(): String = get(Keys.TIME_ZONE, java.util.TimeZone.getDefault().id)

    /** His minutes for planning per weekday, Monday first, as he set them in Plan; null when not set. */
    suspend fun planHours(): List<Double>? = com.clarity.life.core.PlanLoad.parseHours(dao.get(Keys.PLAN_HOURS))

    /** Set by his tap in Plan only; null clears them (not set). */
    suspend fun setPlanHours(hours: List<Double>?) =
        set(Keys.PLAN_HOURS, hours?.let { com.clarity.life.core.PlanLoad.storeHours(it) } ?: "")

    fun watchPlanHours(): kotlinx.coroutines.flow.Flow<List<Double>?> = dao.watchAll()
        .map { rows -> com.clarity.life.core.PlanLoad.parseHours(rows.firstOrNull { it.key == Keys.PLAN_HOURS }?.value) }
        .distinctUntilChanged()

    /**
     * His time zone and day boundary, live. A screen that read them once kept the old day after he changed
     * "when your day ends" in Settings, while the coach (which reads them on every message) had the new one.
     */
    fun watchDay(): kotlinx.coroutines.flow.Flow<Day> = watchDay(dao.watchAll(), phoneZone)

    data class Day(val timeZone: String, val boundaryHour: Int)

    /** The main provider, as a ready-to-use endpoint. Null when he has not pasted a key yet. */
    suspend fun chatEndpoint(chatType: String? = null): Endpoint? {
        val provider = get(Keys.PROVIDER, DefaultSettings.PROVIDER)
        val key = secrets.apiKey(provider)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val thinking = chatThinking(chatType)
        return Endpoint(
            provider = provider,
            baseUrl = get(Keys.BASE_URL, DefaultSettings.BASE_URL).trim(),
            apiKey = key,
            model = get(Keys.CHAT_MODEL, DefaultSettings.CHAT_MODEL).trim(),
            maxOutputTokens = getInt(Keys.MAX_OUTPUT_TOKENS, DefaultSettings.MAX_OUTPUT_TOKENS),
            thinking = thinking != Thinking.OFF,
            effort = thinking,
        )
    }

    suspend fun chatThinking(chatType: String? = null): String {
        val (key, default) = when (chatType) {
            "free" -> Keys.CHAT_THINKING_FREE to DefaultSettings.CHAT_THINKING_FREE
            "untangle" -> Keys.CHAT_THINKING_UNTANGLE to DefaultSettings.CHAT_THINKING_UNTANGLE
            "decide" -> Keys.CHAT_THINKING_DECIDE to DefaultSettings.CHAT_THINKING_DECIDE
            "plan" -> Keys.CHAT_THINKING_PLAN to DefaultSettings.CHAT_THINKING_PLAN
            else -> Keys.CHAT_THINKING to DefaultSettings.CHAT_THINKING
        }
        val stored = dao.get(key)
        if (stored != null) return Thinking.parse(stored, default)
        val global = dao.get(Keys.CHAT_THINKING)
        return if (global != null) Thinking.parse(global, default) else default
    }

    suspend fun letterThinking(): String =
        Thinking.parse(dao.get(Keys.LETTER_THINKING), DefaultSettings.LETTER_THINKING)

    suspend fun backgroundProvider(): String {
        val custom = get(Keys.BACKGROUND_PROVIDER, "").trim()
        return if (custom.isNotEmpty()) custom else get(Keys.PROVIDER, DefaultSettings.PROVIDER)
    }

    suspend fun backgroundBaseUrl(): String {
        val custom = get(Keys.BACKGROUND_BASE_URL, "").trim()
        if (custom.isNotEmpty()) return custom
        val bgProvider = backgroundProvider()
        val chatProvider = get(Keys.PROVIDER, DefaultSettings.PROVIDER)
        if (bgProvider == chatProvider) {
            return get(Keys.BASE_URL, DefaultSettings.BASE_URL).trim()
        }
        return when (bgProvider) {
            "gemini" -> DefaultSettings.GEMINI_BASE_URL
            "anthropic" -> DefaultSettings.ANTHROPIC_BASE_URL
            "deepseek" -> DefaultSettings.BASE_URL
            else -> ""
        }
    }

    suspend fun backgroundApiKey(): String? =
        secrets.backgroundApiKey()?.takeIf { it.isNotBlank() }
            ?: secrets.apiKey(backgroundProvider())?.takeIf { it.isNotBlank() }
            ?: secrets.apiKey(get(Keys.PROVIDER, DefaultSettings.PROVIDER))?.takeIf { it.isNotBlank() }

    /**
     * Background jobs (C4): independent provider, key and model if configured; defaults to the chat ones.
     * The fast model, no thinking and a low temperature for the strict-JSON jobs (reflection, monthly review),
     * thinking at [DefaultSettings.BACKGROUND_THINKING] for the weekly review.
     */
    suspend fun backgroundEndpoint(json: Boolean): Endpoint? {
        val bgProvider = backgroundProvider()
        val key = backgroundApiKey() ?: return null
        val base = Endpoint(
            provider = bgProvider,
            baseUrl = backgroundBaseUrl(),
            apiKey = key,
            model = get(Keys.BACKGROUND_MODEL, DefaultSettings.BACKGROUND_MODEL).trim(),
            maxOutputTokens = getInt(Keys.MAX_OUTPUT_TOKENS, DefaultSettings.MAX_OUTPUT_TOKENS),
            thinking = false,
            effort = Thinking.OFF,
        )
        return background(base, base.model, json)
    }

    /**
     * Fallback endpoint for background jobs (C4): when a separate background provider fails, that job falls back
     * to the main chat provider. Returns null if background is already identical to chat.
     */
    suspend fun fallbackBackgroundEndpoint(json: Boolean): Endpoint? {
        val chat = chatEndpoint() ?: return null
        val bg = backgroundEndpoint(json) ?: return null
        if (chat.provider == bg.provider && chat.baseUrl == bg.baseUrl && chat.apiKey == bg.apiKey &&
            get(Keys.BACKGROUND_MODEL, DefaultSettings.BACKGROUND_MODEL).trim() == chat.model) {
            return null
        }
        return background(chat, chat.model, json)
    }

    /** The weekly letter only: the bigger model (V4 Pro by default), thinking high. */
    suspend fun letterEndpoint(): Endpoint? {
        val base = chatEndpoint() ?: return null
        return letter(base, get(Keys.LETTER_MODEL, DefaultSettings.LETTER_MODEL), letterThinking())
    }

    /**
     * Run once on app start: a model name saved before 10 Oct 2026 (deepseek-reasoner, deepseek-chat) is
     * moved to today's default, so the update brings the faster model without him retyping anything.
     * A name he typed himself that is not on the retired list is never touched.
     */
    suspend fun moveRetiredModels() {
        for (key in listOf(Keys.CHAT_MODEL, Keys.BACKGROUND_MODEL)) {
            val stored = dao.get(key)?.trim() ?: continue
            if (stored in DefaultSettings.RETIRED_MODELS) set(key, DefaultSettings.CHAT_MODEL)
        }
    }

    fun hasKey(provider: String): Boolean = secrets.hasApiKey(provider)

    fun setKey(provider: String, value: String?) = secrets.setApiKey(provider, value?.trim())

    fun hasBackgroundKey(bgProvider: String): Boolean =
        secrets.hasBackgroundApiKey() || secrets.hasApiKey(bgProvider)

    fun setBackgroundKey(value: String?) = secrets.setBackgroundApiKey(value?.trim())

    fun clearBackgroundKey() = secrets.setBackgroundApiKey(null)

    companion object {
        /**
         * The phone's time zone as the screens last saw it. It is not a settings row, so a change of zone (a
         * flight) never reached the screens while the coach, which reads it fresh, had the new one (bug scan
         * 14 Oct 2026, full scan, finding 3). [refreshPhoneZone] runs each time the app comes to the front.
         */
        private val phoneZone = kotlinx.coroutines.flow.MutableStateFlow(java.util.TimeZone.getDefault().id)

        fun refreshPhoneZone() {
            phoneZone.value = java.util.TimeZone.getDefault().id
        }

        /** The live day from the stored rows and the phone's zone: a new value when either changes. */
        fun watchDay(
            rows: kotlinx.coroutines.flow.Flow<List<SettingRow>>,
            zone: kotlinx.coroutines.flow.Flow<String>,
        ): kotlinx.coroutines.flow.Flow<Day> =
            kotlinx.coroutines.flow.combine(rows, zone) { r, z -> day(r.associate { it.key to it.value }, z) }
                .distinctUntilChanged()

        /** The same reading as [timeZone] and [dayBoundaryHour], from the stored rows. */
        fun day(values: Map<String, String>, phoneZone: String = java.util.TimeZone.getDefault().id): Day = Day(
            timeZone = values[Keys.TIME_ZONE] ?: phoneZone,
            boundaryHour = values[Keys.DAY_BOUNDARY_HOUR]?.toIntOrNull() ?: DefaultSettings.DAY_BOUNDARY_HOUR,
        )

        /** Until the database answers: the phone's zone and the default boundary. */
        fun defaultDay(): Day = day(emptyMap())

        /**
         * The chat endpoint made into a background one. Background calls wait up to
         * [DefaultSettings.BACKGROUND_READ_TIMEOUT_S] for the next piece (CLAUDE.md, AI client rules); chat waits 120 s.
         */
        fun background(base: Endpoint, model: String, json: Boolean): Endpoint = base.copy(
            model = model.trim(),
            temperature = if (json) DefaultSettings.BACKGROUND_TEMPERATURE else null,
            thinking = !json,
            effort = if (json) Thinking.OFF else DefaultSettings.BACKGROUND_THINKING,
            readTimeoutS = DefaultSettings.BACKGROUND_READ_TIMEOUT_S,
        )

        /** The chat endpoint made into the weekly letter's: its own model and thinking, the background timeout. */
        fun letter(base: Endpoint, model: String, effort: String): Endpoint = base.copy(
            model = model.trim(),
            temperature = null,
            thinking = effort != Thinking.OFF,
            effort = effort,
            readTimeoutS = DefaultSettings.BACKGROUND_READ_TIMEOUT_S,
        )
    }
}
