package com.aziitech.devmonitor.gateway

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aziitech.devmonitor.gateway.auth.FirebaseAuthIdTokenProvider
import com.aziitech.devmonitor.gateway.auth.FirebaseBearerRequestSigner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var controllerUrl: EditText

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            status.text = "Google sign-in was cancelled."
            return@registerForActivityResult
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                status.text = "Google did not return an ID token. Check the Firebase Web OAuth client configuration."
                return@registerForActivityResult
            }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            lifecycleScope.launch {
                status.text = "Completing Firebase sign-in…"
                try {
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                    // Force one fresh token retrieval during this explicit diagnostic action.
                    FirebaseAuthIdTokenProvider().getIdToken(forceRefresh = true)
                    status.text = "Firebase sign-in successful. A Firebase ID token was obtained securely. The token is not displayed or stored."
                } catch (error: Exception) {
                    status.text = "Firebase sign-in failed: ${error.localizedMessage ?: "Unknown error"}"
                }
            }
        } catch (error: Exception) {
            status.text = "Google sign-in failed: ${error.localizedMessage ?: "Unknown error"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = if (FirebaseAuth.getInstance().currentUser != null) {
                "Firebase user already signed in. Tap the token check to verify token retrieval."
            } else {
                "Sign in with Google to authenticate this Gateway app with Firebase."
            }
            textSize = 16f
            gravity = Gravity.CENTER
        }
        controllerUrl = EditText(this).apply {
            hint = "Controller HTTPS base URL (when deployed)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            singleLine = true
        }

        val signInButton = Button(this).apply {
            text = "Sign in with Google"
            setOnClickListener { beginGoogleSignIn() }
        }
        val tokenButton = Button(this).apply {
            text = "Verify Firebase ID token"
            setOnClickListener { verifyFirebaseToken() }
        }
        val controllerButton = Button(this).apply {
            text = "Test authenticated controller connection"
            setOnClickListener { testControllerConnection() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28)
            addView(TextView(this@MainActivity).apply {
                text = "DevMonitor Gateway"
                textSize = 24f
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 })
            addView(signInButton)
            addView(tokenButton)
            addView(controllerUrl, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 })
            addView(controllerButton)
        }
        setContentView(content)
    }

    private fun beginGoogleSignIn() {
        val webClientId = getString(com.google.android.gms.base.R.string.common_google_play_services_unknown_issue)
        // The Google Services plugin generates this resource from the Gateway app's
        // own google-services.json; use it as the OAuth web client ID.
        val generatedWebClientId = resources.getIdentifier(
            "default_web_client_id", "string", packageName
        )
        if (generatedWebClientId == 0) {
            status.text = "Firebase app configuration is missing. Register this Gateway package in Firebase and add its google-services.json."
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(getString(generatedWebClientId))
            .build()
        signInLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun verifyFirebaseToken() {
        lifecycleScope.launch {
            try {
                FirebaseAuthIdTokenProvider().getIdToken(forceRefresh = true)
                status.text = "Firebase ID token obtained successfully. It was not displayed, logged, or saved."
            } catch (error: Exception) {
                status.text = error.localizedMessage
                    ?: "Sign in with Google first; no Firebase user is currently available."
            }
        }
    }

    private fun testControllerConnection() {
        val baseUrl = controllerUrl.text.toString().trim()
        lifecycleScope.launch {
            try {
                val client = GatewayControllerClient(baseUrl, FirebaseBearerRequestSigner(
                    FirebaseAuthIdTokenProvider()
                ))
                val response = client.listPersonalGateways()
                status.text = "Authenticated controller request succeeded. Response: $response"
            } catch (error: Exception) {
                status.text = "Controller request failed: ${error.localizedMessage ?: "Unknown error"}"
            }
        }
    }
}
