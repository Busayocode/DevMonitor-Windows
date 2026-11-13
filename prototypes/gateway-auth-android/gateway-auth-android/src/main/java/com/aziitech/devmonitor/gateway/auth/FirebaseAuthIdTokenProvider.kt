package com.aziitech.devmonitor.gateway.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Retrieves the signed-in Firebase user's ID token.
 *
 * Firebase manages token caching and refresh. The raw token is returned only
 * to the caller for an HTTPS request; this class never persists or logs it.
 */
class FirebaseAuthIdTokenProvider(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) : IdTokenProvider {

    override suspend fun getIdToken(forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val user = firebaseAuth.currentUser
            ?: throw IllegalStateException("Firebase sign-in is required before using Gateway services")

        val token = user.getIdToken(forceRefresh).await().token
        token?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Firebase did not return an ID token")
    }
}
