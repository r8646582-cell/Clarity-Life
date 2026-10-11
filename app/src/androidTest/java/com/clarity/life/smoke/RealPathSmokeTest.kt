package com.clarity.life.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.DatabaseModule
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.SettingRow
import com.clarity.life.security.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same steps the owner takes on his phone, on the real pieces: the Keystore, and the SQLCipher database
 * opened exactly as the app opens it (MigrationTest uses plain SQLite, so it could not catch a problem here).
 */
@RunWith(AndroidJUnit4::class)
class RealPathSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun savingAnApiKeyRoundTrips() {
        val secrets = SecretStore(context)
        secrets.setApiKey("smoke", "sk-test-1234567890")
        assertTrue(secrets.hasApiKey("smoke"))
        assertEquals("sk-test-1234567890", secrets.apiKey("smoke"))
        secrets.setApiKey("smoke", null)
    }

    @Test
    fun theEncryptedDatabaseOpensAndTakesWrites() = runBlocking {
        val db = DatabaseModule.database(context, SecretStore(context))
        try {
            db.settings().put(SettingRow("smoke", "1"))
            assertEquals("1", db.settings().get("smoke"))
            val id = db.conversations().insert(ConversationRow(chatType = "free", startedAt = 1L))
            db.messages().insert(
                MessageRow(conversationId = id, role = "user", content = "hi", createdAt = 1L, status = "complete"),
            )
            db.conversations().delete(id)
            db.settings().remove("smoke")
        } finally {
            db.close()
        }
    }
}
