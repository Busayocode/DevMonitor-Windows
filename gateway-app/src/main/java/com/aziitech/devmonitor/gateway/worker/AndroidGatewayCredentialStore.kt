package com.aziitech.devmonitor.gateway.worker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredGatewayCredential(val gatewayId: String, val gatewayToken: String, val controllerBaseUrl: String?)

/** Stores the one-time-issued Gateway bearer credential encrypted with an Android Keystore key. */
class AndroidGatewayCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "devmonitor_gateway_identity",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(gatewayId: String, gatewayToken: String, controllerBaseUrl: String) {
        require(gatewayId.isNotBlank() && gatewayToken.isNotBlank())
        require(controllerBaseUrl.startsWith("https://", ignoreCase = true))
        val payload = JSONObject()
            .put("gatewayId", gatewayId)
            .put("gatewayToken", gatewayToken)
            .put("controllerBaseUrl", controllerBaseUrl)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString(CREDENTIAL_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()) { "Could not securely save the Gateway credential" }
    }

    @Synchronized
    fun load(): StoredGatewayCredential? {
        val encoded = preferences.getString(CREDENTIAL_KEY, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "Stored Gateway credential is malformed" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        val payload = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        return StoredGatewayCredential(
            gatewayId = payload.getString("gatewayId"),
            gatewayToken = payload.getString("gatewayToken"),
            controllerBaseUrl = payload.optString("controllerBaseUrl").takeIf(String::isNotBlank)
        )
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(CREDENTIAL_KEY).commit()) {
            "Could not clear the stored Gateway credential"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "devmonitor.gateway.identity.aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val CREDENTIAL_KEY = "encrypted_gateway_credential"
    }
}
