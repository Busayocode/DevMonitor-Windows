package com.aziitech.devmonitor.gateway.worker

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
 * Keeps opaque controller lease tokens encrypted at rest with an Android Keystore AES key.
 * The raw token is deliberately not written to the SQLite job/attempt ledger or logs.
 */
class AndroidGatewayLeaseTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "devmonitor_gateway_lease_secrets",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun put(attemptId: String, leaseToken: String) {
        require(attemptId.isNotBlank() && leaseToken.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(leaseToken.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(preferences.edit()
            .putString(storageKey(attemptId), Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()) { "Could not persist the encrypted Gateway lease token" }
    }

    @Synchronized
    fun get(attemptId: String): String? {
        require(attemptId.isNotBlank())
        val encoded = preferences.getString(storageKey(attemptId), null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES) { "Stored Gateway lease token is malformed" }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    @Synchronized
    fun remove(attemptId: String) {
        require(attemptId.isNotBlank())
        check(preferences.edit().remove(storageKey(attemptId)).commit()) {
            "Could not remove the Gateway lease token"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun storageKey(attemptId: String) = "lease_token_$attemptId"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "devmonitor.gateway.lease-token.aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
