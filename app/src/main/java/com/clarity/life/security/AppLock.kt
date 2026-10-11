package com.clarity.life.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager

/**
 * App lock and hide in recents (spec section 9, both on by default), with Android's own screens and no extra
 * library: the fingerprint or face the phone already knows, or its PIN, pattern or password.
 *
 * A phone with no screen lock at all cannot lock the app, so nothing is asked (the Settings line says so).
 * Coming back within [GRACE_MS] does not ask again, so opening the file picker for a backup is not a lock.
 */
object AppLock {

    const val GRACE_MS = 60_000L
    const val CONFIRM_REQUEST = 4_201

    /** True when the phone has a PIN, pattern, password or biometric set up. */
    fun canLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Asks for the fingerprint, face or screen lock; [onUnlocked] runs on success. */
    @Suppress("DEPRECATION")
    fun ask(activity: Activity, onUnlocked: () -> Unit) {
        if (!canLock(activity)) {
            onUnlocked()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Unlock Clarity")
                .setSubtitle("Your conversations stay private")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onUnlocked()
                },
            )
        } else {
            // Android 8 and 9: the screen lock's own confirm screen (the prompt can only offer the PIN from
            // Android 10); MainActivity hears the result.
            val intent = activity.getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent("Unlock Clarity", "Your conversations stay private")
            if (intent == null) onUnlocked() else activity.startActivityForResult(intent, CONFIRM_REQUEST)
        }
    }

    /** Hide in recents: the app switcher shows a blank card instead of the conversation. */
    fun hideInRecents(activity: Activity, hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            // Older Android has no switch for the recents card alone; this also blocks screenshots of the app.
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
