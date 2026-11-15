package com.aziitech.devmonitor.gateway

import android.os.Bundle
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.aziitech.devmonitor.gateway.auth.FirebaseAuthIdTokenProvider
import com.aziitech.devmonitor.gateway.auth.FirebaseBearerRequestSigner
import com.aziitech.devmonitor.gateway.ui.GatewayAuroraTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Sign in with Google to authenticate this dedicated Gateway phone.")
    private var controllerUrl by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            status = "Google sign-in was cancelled."
            busy = false
            return@registerForActivityResult
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                status = "Google did not return an ID token. Check the Firebase Web OAuth client configuration."
                busy = false
                return@registerForActivityResult
            }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            lifecycleScope.launch {
                busy = true
                status = "Completing secure Firebase sign-in…"
                try {
                    FirebaseAuth.getInstance().signInWithCredential(credential).await()
                    FirebaseAuthIdTokenProvider().getIdToken(forceRefresh = true)
                    signedIn = FirebaseAuth.getInstance().currentUser != null
                    status = "Google sign-in successful. A Firebase ID token was obtained securely; it is not displayed or stored."
                } catch (error: Exception) {
                    status = "Firebase sign-in failed: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    busy = false
                }
            }
        } catch (error: Exception) {
            status = "Google sign-in failed: ${error.localizedMessage ?: "Unknown error"}"
            busy = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signedIn = FirebaseAuth.getInstance().currentUser != null
        if (signedIn) {
            status = "A Firebase account is already signed in. Verify the session or connect to the controller."
        }

        setContent {
            GatewayAuroraTheme(darkTheme = true) {
                GatewayHomeScreen(
                    signedIn = signedIn,
                    status = status,
                    controllerUrl = controllerUrl,
                    busy = busy,
                    onControllerUrlChange = { controllerUrl = it },
                    onSignIn = { beginGoogleSignIn() },
                    onVerifyToken = { verifyFirebaseToken() },
                    onTestController = { testControllerConnection() }
                )
            }
        }
    }

    private fun beginGoogleSignIn() {
        val generatedWebClientId = resources.getIdentifier(
            "default_web_client_id", "string", packageName
        )
        if (generatedWebClientId == 0) {
            status = "Firebase app configuration is missing. Register this Gateway package in Firebase and add its google-services.json."
            return
        }

        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(getString(generatedWebClientId))
            .build()
        status = "Opening secure Google sign-in…"
        signInLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun verifyFirebaseToken() {
        lifecycleScope.launch {
            busy = true
            status = "Verifying secure Firebase session…"
            try {
                FirebaseAuthIdTokenProvider().getIdToken(forceRefresh = true)
                signedIn = FirebaseAuth.getInstance().currentUser != null
                status = "Firebase ID token obtained successfully. It was not displayed, logged, or saved."
            } catch (error: Exception) {
                status = error.localizedMessage
                    ?: "Sign in with Google first; no Firebase user is currently available."
            } finally {
                busy = false
            }
        }
    }

    private fun testControllerConnection() {
        val baseUrl = controllerUrl.trim()
        lifecycleScope.launch {
            busy = true
            status = "Testing authenticated controller connection…"
            try {
                val client = GatewayControllerClient(
                    baseUrl,
                    FirebaseBearerRequestSigner(FirebaseAuthIdTokenProvider())
                )
                client.listPersonalGateways()
                status = "Authenticated controller request succeeded."
            } catch (error: Exception) {
                status = "Controller request failed: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }
}
