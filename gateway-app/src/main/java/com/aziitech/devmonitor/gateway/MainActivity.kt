package com.aziitech.devmonitor.gateway

import android.os.Bundle
import android.os.Build
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
import com.aziitech.devmonitor.gateway.worker.AndroidGatewayCredentialStore
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Sign in with Google to authenticate this dedicated Gateway phone.")
    private var controllerUrl by mutableStateOf("")
    private var gatewayDisplayName by mutableStateOf(Build.MODEL.orEmpty().take(80).ifBlank { "DevMonitor Gateway phone" })
    private var gatewayId by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    private val firebaseTokens by lazy { FirebaseAuthIdTokenProvider() }
    private val credentialStore by lazy { AndroidGatewayCredentialStore(applicationContext) }

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
                    firebaseTokens.getIdToken(forceRefresh = true)
                    signedIn = FirebaseAuth.getInstance().currentUser != null
                    status = "Google sign-in successful. Firebase identity is ready; its token is not displayed or stored."
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
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { credentialStore.load() }
                gatewayId = saved?.gatewayId
                if (saved != null) status = "This phone is already registered as Gateway ${saved.gatewayId}."
            } catch (error: Exception) {
                status = "A saved Gateway credential could not be unlocked. Registration is paused to avoid creating a duplicate identity. ${error.localizedMessage ?: ""}"
            }
        }
        if (signedIn && status.startsWith("Sign in")) {
            status = "A Firebase account is already signed in. Verify the session or register this phone."
        }

        setContent {
            GatewayAuroraTheme(darkTheme = true) {
                GatewayHomeScreen(
                    signedIn = signedIn,
                    status = status,
                    controllerUrl = controllerUrl,
                    gatewayDisplayName = gatewayDisplayName,
                    gatewayId = gatewayId,
                    busy = busy,
                    onControllerUrlChange = { controllerUrl = it },
                    onGatewayDisplayNameChange = { gatewayDisplayName = it.take(80) },
                    onSignIn = { beginGoogleSignIn() },
                    onVerifyToken = { verifyFirebaseToken() },
                    onTestController = { testControllerConnection() },
                    onRegisterGateway = { registerGateway() }
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
                firebaseTokens.getIdToken(forceRefresh = true)
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
                    FirebaseBearerRequestSigner(firebaseTokens)
                )
                client.listPersonalGateways()
                status = "Authenticated Firebase client request succeeded."
            } catch (error: Exception) {
                status = "Controller request failed: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }

    private fun registerGateway() {
        val baseUrl = controllerUrl.trim()
        val displayName = gatewayDisplayName.trim()
        if (!signedIn || FirebaseAuth.getInstance().currentUser == null) {
            status = "Sign in with Google before registering this phone."
            return
        }
        if (gatewayId != null) {
            status = "This installation is already registered as Gateway $gatewayId. It will not create a duplicate registration."
            return
        }
        if (baseUrl.isBlank() || displayName.isBlank()) {
            status = "Enter the HTTPS controller URL and a name for this Gateway phone."
            return
        }
        lifecycleScope.launch {
            busy = true
            status = "Registering this phone securely with the controller…"
            try {
                val firebaseIdToken = firebaseTokens.getIdToken(forceRefresh = true)
                val client = GatewayWorkerControllerClient(
                    baseUrl = baseUrl,
                    gatewayTokenProvider = {
                        withContext(Dispatchers.IO) {
                            credentialStore.load()?.gatewayToken
                                ?: throw IllegalStateException("This phone has no saved Gateway credential")
                        }
                    }
                )
                val registered = client.registerGateway(displayName, firebaseIdToken, credentialStore)
                gatewayId = registered.gatewayId
                status = "Gateway registration succeeded. Its private controller credential is encrypted on this phone."
            } catch (error: Exception) {
                status = "Gateway registration failed: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }
}
