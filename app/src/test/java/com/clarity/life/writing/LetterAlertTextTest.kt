package com.clarity.life.writing

import org.junit.Assert.assertTrue
import org.junit.Test

class LetterAlertTextTest {

    @Test
    fun theChannelNamesTheSettingsGroupWhereTheSwitchReallyIs() {
        // The Sunday alert switch lives in Settings, "Letters and your day" (SettingsScreen); there is no
        // "Reminders" group.
        assertTrue(LetterWriter.CHANNEL_DESCRIPTION.contains("Settings, Letters and your day, Sunday alert"))
    }
}
