package com.clarity.life.settings

/**
 * The only place in the app where a provider, a base URL or a model name is written down
 * (CLAUDE.md rule 9). Everything here is a *default*: each value is a setting the owner can change in
 * Settings, so a retired model never needs a new APK.
 *
 * Checked 10 Oct 2026 on DeepSeek's pricing page and thinking mode guide: base URL https://api.deepseek.com,
 * `deepseek-flash` is DeepSeek-V4.1-Flash and `deepseek-v4-pro` is DeepSeek-V4-Pro. Both think by default;
 * the request turns thinking off (`thinking: {type: disabled}`) or sets `reasoning_effort` (low, high, max).
 * The old ids deepseek-reasoner and deepseek-chat are moved to the new default once ([SettingsRepository]).
 * Model names stay free text fields, so a rename never needs a new APK.
 */
object DefaultSettings {
    const val PROVIDER = "deepseek"
    const val BASE_URL = "https://api.deepseek.com"
    /** Fast, and good enough for chat at a low thinking level (the owner's choice, 10 Oct 2026). */
    const val CHAT_MODEL = "deepseek-flash"
    const val CHAT_THINKING = "low"

    /** Reflection, reviews and chapters run on the fast model too. */
    const val BACKGROUND_MODEL = CHAT_MODEL

    /** Thinking for the background jobs that write prose (the weekly review). JSON jobs never think. */
    const val BACKGROUND_THINKING = "high"

    /** Only the weekly letter uses the bigger model: it is once a week and the most careful writing. */
    const val LETTER_MODEL = "deepseek-v4-pro"
    const val LETTER_THINKING = "high"

    /** Stored model names from before 10 Oct 2026, moved once to the new defaults. */
    val RETIRED_MODELS = setOf("deepseek-reasoner", "deepseek-chat")

    /**
     * The address filled in when he picks the Anthropic preset in Settings, Coach. There is no backup
     * provider: only the provider he chose is ever called (his decision, 11 Oct 2026).
     */
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"

    /** Thinking tokens count against the output limit, so this is set high; there is no reply cap in the app. */
    const val MAX_OUTPUT_TOKENS = 16000
    const val BACKGROUND_TEMPERATURE = 0.3

    /** Stage B. CLS pooling, 384 dimensions (spec section 7). */
    const val EMBEDDING_MODEL = "bge-small-en-v1.5"

    /** A 12:30 am check-in belongs to the day before (spec section 2.4). */
    const val DAY_BOUNDARY_HOUR = 4

    const val WEEKLY_LETTER_ON = true
    // On, silent: the spec's recommendation (section 17), chosen by the owner in the audit follow-up of 10 Oct 2026.
    // A phone that already saved the switch keeps what it saved.
    const val SUNDAY_ALERT_ON = true

    /** The morning brief (spec section 8): on by default, the owner's choice of 13 Oct 2026. */
    const val MORNING_BRIEF_ON = true

    /** Empty: the app ships for anyone. The person types a name in Settings, Coach, if they want one used. */
    const val USER_NAME = ""
    /** What fills {{USER_NAME}} while no name is set. */
    const val UNNAMED = "this person"
    const val APP_LOCK_ON = true
    const val HIDE_IN_RECENTS_ON = true

    /** Timeouts, in seconds (CLAUDE.md, AI client rules). */
    const val CONNECT_TIMEOUT_S = 30L
    const val CHAT_GAP_TIMEOUT_S = 120L
    const val BACKGROUND_READ_TIMEOUT_S = 300L
}
