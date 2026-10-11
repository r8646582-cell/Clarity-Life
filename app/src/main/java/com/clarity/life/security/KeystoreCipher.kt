package com.clarity.life.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AES-GCM wrapper around a key that never leaves the Android Keystore, so the phone's hardware holds
 * the only copy. Used to seal the database passphrase and the API keys; nothing secret is ever written in
 * the clear, logged, or sent anywhere.
 *
 * Adapted from the old repo's security/KeystoreCipher.kt.
 */
class KeystoreCipher(private val alias: String) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /** Opening never makes a new key: a lost Keystore key must fail plainly, not as a confusing bad-tag error. */
    @Synchronized
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns iv + ciphertext, Base64 encoded, safe to keep in SharedPreferences. */
    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    fun open(sealed: String): ByteArray {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, checkNotNull(existingKey()) { "keystore key missing" }, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
        return cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun prefs(context: Context) = context.getSharedPreferences("clarity_secrets", Context.MODE_PRIVATE)
    }
}
