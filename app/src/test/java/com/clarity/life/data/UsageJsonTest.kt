package com.clarity.life.data

import com.clarity.life.data.entity.JobUsage
import com.clarity.life.data.entity.UsageJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that job usage accumulates properly and decodes cleanly without data loss.
 */
class UsageJsonTest {

    @Test
    fun emptyOrBlankDecodesToEmptyMap() {
        assertTrue(UsageJson.decode("").isEmpty())
        assertTrue(UsageJson.decode("{}").isEmpty())
        assertTrue(UsageJson.decode("   ").isEmpty())
        assertTrue(UsageJson.decode("invalid json").isEmpty())
    }

    @Test
    fun addAccumulatesAcrossJobs() {
        var json = "{}"
        json = UsageJson.add(json, "chat", calls = 1, tokensIn = 1000, tokensOut = 150, tokensCached = 800)
        json = UsageJson.add(json, "chat", calls = 1, tokensIn = 1200, tokensOut = 100, tokensCached = 900)
        json = UsageJson.add(json, "card_followup", calls = 1, tokensIn = 500, tokensOut = 20, tokensCached = 450)
        json = UsageJson.add(json, "memory", calls = 1, tokensIn = 300, tokensOut = 50, tokensCached = 0)

        val map = UsageJson.decode(json)
        assertEquals(3, map.size)

        val chat = map["chat"]
        assertEquals(JobUsage(calls = 2, tokensIn = 2200, tokensOut = 250, tokensCached = 1700), chat)

        val followup = map["card_followup"]
        assertEquals(JobUsage(calls = 1, tokensIn = 500, tokensOut = 20, tokensCached = 450), followup)

        val memory = map["memory"]
        assertEquals(JobUsage(calls = 1, tokensIn = 300, tokensOut = 50, tokensCached = 0), memory)
    }
}
