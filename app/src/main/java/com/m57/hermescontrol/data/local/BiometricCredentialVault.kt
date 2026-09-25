package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Stores dashboard basic-auth credentials so expired sessions can be restored
 * without retyping — but **only** after a biometric unlock.
 *
 * The password is encrypted with an AndroidKeyStore AES key that requires
 * [BIOMETRIC_STRONG] authentication for every encrypt/decrypt. Reading the
 * ciphertext from prefs without biometrics cannot recover the password.
 */
object BiometricCredentialVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "hermes_dashboard_cred_aes"
    private const val PREFS_USERNAME = "biometric_saved_username"
    private const val PREFS_CIPHERTEXT = "biometric_saved_password_ct"
    private const val PREFS_IV = "biometric_saved_password_iv"
    private const val GCM_TAG_BITS = 128

    data class Credentials(
        val username: String,
        val password: String,
    )

    sealed class Availability {
        data object Available : Availability()

        data object NoneEnrolled : Availability()

        data object Unavailable : Availability()
    }

    fun availability(context: Context): Availability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NoneEnrolled
            else -> Availability.Unavailable
        }

    fun hasSavedCredentials(prefs: SharedPreferences): Boolean {
        val user = prefs.getString(PREFS_USERNAME, null)
        val ct = prefs.getString(PREFS_CIPHERTEXT, null)
        val iv = prefs.getString(PREFS_IV, null)
        return !user.isNullOrBlank() && !ct.isNullOrBlank() && !iv.isNullOrBlank()
    }

    /** Username is non-secret metadata used to label the unlock button. */
    fun savedUsername(prefs: SharedPreferences): String? =
        prefs.getString(PREFS_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun clear(prefs: SharedPreferences) {
        prefs
            .edit()
            .remove(PREFS_USERNAME)
            .remove(PREFS_CIPHERTEXT)
            .remove(PREFS_IV)
            .apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Encrypt and persist credentials after the user authenticates with a
     * biometric [Cipher] in ENCRYPT mode (from [createEncryptCipher]).
     */
    fun saveAfterAuthenticated(
        prefs: SharedPreferences,
        cipher: Cipher,
        username: String,
        password: String,
    ) {
        require(username.isNotBlank() && password.isNotBlank())
        val ciphertext = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        prefs
            .edit()
            .putString(PREFS_USERNAME, username.trim())
            .putString(PREFS_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREFS_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Decrypt the stored password after the user authenticates with a
     * biometric [Cipher] in DECRYPT mode (from [createDecryptCipher]).
     */
    fun unlockAfterAuthenticated(
        prefs: SharedPreferences,
        cipher: Cipher,
    ): Credentials {
        val username =
            prefs.getString(PREFS_USERNAME, null)?.takeIf { it.isNotBlank() }
                ?: error("No saved username")
        val ctB64 =
            prefs.getString(PREFS_CIPHERTEXT, null)
                ?: error("No saved password ciphertext")
        val plaintext =
            cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
        return Credentials(
            username = username,
            password = String(plaintext, StandardCharsets.UTF_8),
        )
    }

    fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher
    }

    fun createDecryptCipher(prefs: SharedPreferences): Cipher {
        val ivB64 =
            prefs.getString(PREFS_IV, null)
                ?: error("No saved IV")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val builder =
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(0)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeButton: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            onError("Biometric crypto object missing")
                            return
                        }
                        onSuccess(authenticatedCipher)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        ) {
                            onCancel()
                        } else {
                            onError(errString.toString())
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // Keep the prompt open; user can retry.
                    }
                },
            )

        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButton)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Suspends until the biometric prompt completes. Returns the authenticated
     * [Cipher] on success, or null when the user cancels / auth fails.
     */
    suspend fun authenticateSuspend(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeButton: String,
        cipher: Cipher,
    ): Cipher? =
        suspendCancellableCoroutine { cont ->
            authenticate(
                activity = activity,
                title = title,
                subtitle = subtitle,
                negativeButton = negativeButton,
                cipher = cipher,
                onSuccess = { authenticated ->
                    if (cont.isActive) cont.resume(authenticated)
                },
                onError = {
                    if (cont.isActive) cont.resume(null)
                },
                onCancel = {
                    if (cont.isActive) cont.resume(null)
                },
            )
        }
}
