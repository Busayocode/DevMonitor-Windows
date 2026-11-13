package com.aziitech.devmonitor.gateway.auth

/**
 * Supplies a Firebase Authentication ID token for Gateway API requests.
 *
 * This is deliberately separate from Google Drive OAuth access tokens.
 */
fun interface IdTokenProvider {
    suspend fun getIdToken(forceRefresh: Boolean): String
}
