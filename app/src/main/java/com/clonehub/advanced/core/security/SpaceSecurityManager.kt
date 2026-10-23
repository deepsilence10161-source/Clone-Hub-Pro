package com.clonehub.advanced.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 100% Secure Cryptographic Manager for Spaces & App Lock.
 * Hardware-backed AES-256-GCM encryption with Android KeyStore.
 */
class SpaceSecurityManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("security_vault", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val KEY_ALIAS = "clonehub_vault_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        @Volatile
        private var INSTANCE: SpaceSecurityManager? = null

        fun getInstance(context: Context): SpaceSecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpaceSecurityManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        ensureSecretKeyExists()
    }

    private fun ensureSecretKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keyGenSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }

    /**
     * Checks if a space has a PIN lock enabled.
     */
    fun isSpaceLocked(spaceId: Int): Boolean {
        return prefs.contains("pin_space_$spaceId")
    }

    /**
     * Sets or updates the PIN lock for a space.
     */
    fun setSpacePin(spaceId: Int, pin: String) {
        val encrypted = encryptString(pin)
        prefs.edit().putString("pin_space_$spaceId", encrypted).apply()
    }

    /**
     * Verifies if entered PIN matches the space's locked PIN.
     */
    fun verifySpacePin(spaceId: Int, enteredPin: String): Boolean {
        val encrypted = prefs.getString("pin_space_$spaceId", null) ?: return true
        val decrypted = decryptString(encrypted)
        return decrypted == enteredPin
    }

    /**
     * Removes PIN lock from a space.
     */
    fun removeSpacePin(spaceId: Int) {
        prefs.edit().remove("pin_space_$spaceId").apply()
    }

    /**
     * Disguise Calculator mode: Clone Hub opens as a fully functional Calculator.
     */
    var isDisguiseModeEnabled: Boolean
        get() = prefs.getBoolean("disguise_calculator_enabled", false)
        set(value) = prefs.edit().putBoolean("disguise_calculator_enabled", value).apply()

    private fun encryptString(plainText: String): String {
        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptString(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12) // GCM IV is 12 bytes
        val cipherText = combined.copyOfRange(12, combined.size)

        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedBytes = cipher.doFinal(cipherText)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
