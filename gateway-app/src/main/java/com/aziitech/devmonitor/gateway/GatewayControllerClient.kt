package com.aziitech.devmonitor.gateway

import com.aziitech.devmonitor.gateway.auth.FirebaseBearerRequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Minimal authenticated client for the existing controller's personal-gateway
 * listing endpoint. The deployment URL is supplied at runtime; none is guessed
 * or hard-coded into the app.
 */
class GatewayControllerClient(
    baseUrl: String,
    private val signer: FirebaseBearerRequestSigner,
    private val http: OkHttpClient = OkHttpClient()
) {
    private val root = requireNotNull(baseUrl.trim().toHttpUrlOrNull()) {
        "Enter a valid controller URL"
    }

    init {
        require(root.isHttps) { "Gateway controller must use HTTPS" }
    }

    suspend fun listPersonalGateways(): String = withContext(Dispatchers.IO) {
        val url = root.newBuilder()
            .addPathSegments("v1/client/gateways")
            .build()
        val unsigned = Request.Builder().url(url).get().build()
        val signed = signer.sign(unsigned)
        http.newCall(signed).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Controller returned HTTP ${response.code}: ${body.take(240)}")
            }
            body
        }
    }
}
