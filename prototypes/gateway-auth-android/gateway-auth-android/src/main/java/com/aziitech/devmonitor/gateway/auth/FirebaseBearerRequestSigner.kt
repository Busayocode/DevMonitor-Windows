package com.aziitech.devmonitor.gateway.auth

import okhttp3.Request

/**
 * Adds the current Firebase ID token to a Gateway controller request.
 *
 * HTTPS is mandatory so the bearer token cannot be sent over clear-text HTTP.
 */
class FirebaseBearerRequestSigner(
    private val tokenProvider: IdTokenProvider,
) {
    suspend fun sign(request: Request): Request {
        if (!request.url.isHttps) {
            throw SecurityException("Gateway authentication requires HTTPS")
        }

        val token = tokenProvider.getIdToken(forceRefresh = false)
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }
}
