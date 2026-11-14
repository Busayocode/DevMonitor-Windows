package com.aziitech.devmonitor.gateway.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseBearerRequestSignerTest {

    private class FakeTokenProvider : IdTokenProvider {
        var requestedForceRefresh: Boolean? = null

        override suspend fun getIdToken(forceRefresh: Boolean): String {
            requestedForceRefresh = forceRefresh
            return "test-firebase-id-token"
        }
    }

    @Test
    fun addsFirebaseBearerTokenToHttpsRequest() = runBlocking {
        val provider = FakeTokenProvider()
        val signer = FirebaseBearerRequestSigner(provider)
        val original = Request.Builder()
            .url("https://gateway.example.test/v1/client/session")
            .build()

        val signed = signer.sign(original)

        assertEquals("Bearer test-firebase-id-token", signed.header("Authorization"))
        assertEquals(false, provider.requestedForceRefresh)
    }

    @Test
    fun refusesToSendBearerTokenOverHttp() = runBlocking {
        val provider = FakeTokenProvider()
        val signer = FirebaseBearerRequestSigner(provider)
        val insecure = Request.Builder()
            .url("http://gateway.example.test/v1/client/session")
            .build()

        assertThrows(SecurityException::class.java) {
            runBlocking { signer.sign(insecure) }
        }
        assertEquals(null, provider.requestedForceRefresh)
    }
}
