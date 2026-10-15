package com.clarity.life.data.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Breakdown of tokens and cache share for an individual AI job (spec C1: cost broken down by job in Health).
 */
@Serializable
data class JobUsage(
    val calls: Long = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tokensCached: Long = 0,
)

object UsageJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): Map<String, JobUsage> = try {
        if (text.isBlank() || text == "{}") emptyMap() else json.decodeFromString(text)
    } catch (_: Exception) {
        emptyMap()
    }

    fun encode(map: Map<String, JobUsage>): String = json.encodeToString(map)

    fun add(
        existingJson: String,
        job: String,
        calls: Long,
        tokensIn: Long,
        tokensOut: Long,
        tokensCached: Long,
    ): String {
        val current = decode(existingJson).toMutableMap()
        val prev = current[job] ?: JobUsage()
        current[job] = prev.copy(
            calls = prev.calls + calls,
            tokensIn = prev.tokensIn + tokensIn,
            tokensOut = prev.tokensOut + tokensOut,
            tokensCached = prev.tokensCached + tokensCached,
        )
        return encode(current)
    }
}
