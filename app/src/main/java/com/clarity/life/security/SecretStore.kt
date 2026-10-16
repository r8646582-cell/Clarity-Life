package com.clarity.life.security

import android.content.Context
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the secrets live: the database passphrase and the API keys, each sealed with a Keystore key
 * ([KeystoreCipher]) and kept in the app's private preferences. Never in the database, never in a log,
 * never in a backup, never sent anywhere except as the Authorization header of a call to his own provider.
 */
@Singleton
class SecretStore @Inject constructor(private val context: Context) {

    private val prefs = KeystoreCipher.prefs(context)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    /** 32 random bytes, made once on first launch, that unlock the SQLCipher database. */
    fun databaseKey(): ByteArray {
        prefs.getString(DB_KEY, null)?.let { return cipher.open(it) }
        // A database without its saved key can never be opened; a fresh key would only hide that and crash later.
        check(!context.getDatabasePath(com.clarity.life.data.ClarityDatabase.NAME).exists()) { "database key missing" }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Written before the database is created with it: an asynchronous write lost to a crash here would
        // leave an encrypted database whose key exists nowhere.
        check(prefs.edit().putString(DB_KEY, cipher.seal(fresh)).commit()) { "database key not saved" }
        return fresh
    }

    fun apiKey(provider: String): String? =
        prefs.getString(apiKeyName(provider), null)?.let { String(cipher.open(it), Charsets.UTF_8) }

    fun setApiKey(provider: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) {
            editor.remove(apiKeyName(provider))
        } else {
            editor.putString(apiKeyName(provider), cipher.seal(value.toByteArray(Charsets.UTF_8)))
        }
        editor.apply()
    }

    fun hasApiKey(provider: String): Boolean = prefs.contains(apiKeyName(provider))

    fun backgroundApiKey(): String? =
        prefs.getString(KEY_BACKGROUND_API, null)?.let { String(cipher.open(it), Charsets.UTF_8) }

    fun setBackgroundApiKey(value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) {
            editor.remove(KEY_BACKGROUND_API)
        } else {
            editor.putString(KEY_BACKGROUND_API, cipher.seal(value.toByteArray(Charsets.UTF_8)))
        }
        editor.apply()
    }

    fun hasBackgroundApiKey(): Boolean = prefs.contains(KEY_BACKGROUND_API)

    /**
     * The backup passphrase, sealed like the API keys, so the weekly backup can run while he is away. It never
     * leaves the phone and is never shown again; he keeps it on paper (spec section 9).
     */
    fun backupPassphrase(): CharArray? =
        prefs.getString(BACKUP_PASSPHRASE, null)?.let { String(cipher.open(it), Charsets.UTF_8).toCharArray() }

    fun setBackupPassphrase(value: CharArray?) {
        val editor = prefs.edit()
        if (value == null || value.isEmpty()) {
            editor.remove(BACKUP_PASSPHRASE)
        } else {
            editor.putString(BACKUP_PASSPHRASE, cipher.seal(String(value).toByteArray(Charsets.UTF_8)))
        }
        editor.commit()
    }

    fun hasBackupPassphrase(): Boolean = prefs.contains(BACKUP_PASSPHRASE)

    /** "Erase everything": the sealed secrets go too, so the old database can never be opened again. */
    fun eraseEverything() {
        prefs.edit().clear().commit()
        cipher.deleteKey()
    }

    private fun apiKeyName(provider: String) = "api_key_$provider"

    companion object {
        private const val KEY_ALIAS = "clarity_secrets_v1"
        private const val KEY_BACKGROUND_API = "bg_api_key"
        private const val BACKUP_PASSPHRASE = "backup_passphrase"
        private const val DB_KEY = "db_key"
    }
}
