package com.aziitech.devmonitor.gateway

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.provider.Settings
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
    private var workerState by mutableStateOf("STOPPED")
    private var batteryOptimizationIgnored by mutableStateOf(false)
    private var smsPermissionsGranted by mutableStateOf(false)
    private var phoneStatePermissionGranted by mutableStateOf(false)
    private var activeSimCount by mutableStateOf(0)
    private var readySimCount by mutableStateOf(0)
    private val firebaseTokens by lazy { FirebaseAuthIdTokenProvider() }
    private val credentialStore by lazy { AndroidGatewayCredentialStore(applicationContext) }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Notification permission was not granted. Android may limit where the foreground notification appears; the worker will still show its status in system task controls."
        launchGatewayWorkerService()
    }

    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refreshSmsReadiness()
        if (results.values.any { !it }) status = "Some Gateway SMS permissions were denied. SMS processing remains disabled."
        else status = "SMS permissions granted. SIM readiness is checked separately; no message was sent or received."
    }

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
        controllerUrl = getSharedPreferences(GatewayForegroundService.PREFERENCES, MODE_PRIVATE)
            .getString(GatewayForegroundService.KEY_CONTROLLER_URL, "").orEmpty()
        workerState = GatewayForegroundService.readState(this)
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { credentialStore.load() }
                gatewayId = saved?.gatewayId
                if (saved != null) {
                    saved.controllerBaseUrl?.let { pinnedUrl ->
                        controllerUrl = pinnedUrl
                        getSharedPreferences(GatewayForegroundService.PREFERENCES, MODE_PRIVATE).edit()
                            .putString(GatewayForegroundService.KEY_CONTROLLER_URL, pinnedUrl).apply()
                    }
                    status = if (saved.controllerBaseUrl == null) {
                        "This phone is registered as Gateway ${saved.gatewayId}, but its controller endpoint is not pinned. Safe service startup is blocked until identity recovery is completed."
                    } else {
                        "This phone is already registered as Gateway ${saved.gatewayId}."
                    }
                }
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
                    workerState = workerState,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    smsPermissionsGranted = smsPermissionsGranted,
                    phoneStatePermissionGranted = phoneStatePermissionGranted,
                    activeSimCount = activeSimCount,
                    readySimCount = readySimCount,
                    onRequestSmsReadiness = { requestSmsReadiness() },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() },
                    onStartWorker = { requestGatewayWorkerStart() },
                    onStopWorker = { stopGatewayWorker() },
                    onControllerUrlChange = {
                        controllerUrl = it
                        getSharedPreferences(GatewayForegroundService.PREFERENCES, MODE_PRIVATE).edit()
                            .putString(GatewayForegroundService.KEY_CONTROLLER_URL, it.trim()).apply()
                    },
                    onGatewayDisplayNameChange = { gatewayDisplayName = it.take(80) },
                    onSignIn = { beginGoogleSignIn() },
                    onVerifyToken = { verifyFirebaseToken() },
                    onTestController = { testControllerConnection() },
                    onRegisterGateway = { registerGateway() }
                )
            }
        }
    }

    private val workerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == GatewayForegroundService.ACTION_STATE_CHANGED) {
                workerState = intent.getStringExtra(GatewayForegroundService.EXTRA_STATE)
                    ?: GatewayForegroundService.readState(this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GatewayForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(workerStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(workerStateReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(workerStateReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        workerState = GatewayForegroundService.readState(this)
        refreshBatteryOptimizationStatus()
        refreshSmsReadiness()
    }

    private fun refreshBatteryOptimizationStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            status = "Android battery-optimization settings opened. Find DevMonitor Gateway and choose the appropriate unrestricted or not-optimized option if you approve."
        }.onFailure {
            status = "Android could not open battery-optimization settings. Open Settings and search for Battery optimization or App battery usage."
        }
    }
    private fun requestSmsReadiness() {
        val permissions = arrayOf(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_PHONE_STATE
        )
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            refreshSmsReadiness()
            status = "SMS permissions are already granted. SIM status refreshed; SMS sending and receiving are not implemented yet."
        } else {
            status = "Android will ask for the permissions needed for the dedicated Gateway role. This does not start an SMS operation."
            smsPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun refreshSmsReadiness() {
        val sendGranted = checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val receiveGranted = checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        phoneStatePermissionGranted = checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        smsPermissionsGranted = sendGranted && receiveGranted
        activeSimCount = 0
        readySimCount = 0
        if (!phoneStatePermissionGranted) return
        runCatching {
            val subscriptions = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val active = subscriptions.activeSubscriptionInfoList.orEmpty()
            activeSimCount = active.size
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            readySimCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                active.count { info ->
                    telephony.createForSubscriptionId(info.subscriptionId).simState == TelephonyManager.SIM_STATE_READY
                }
            } else {
                if (active.isNotEmpty() && telephony.simState == TelephonyManager.SIM_STATE_READY) 1 else 0
            }
        }.onFailure {
            activeSimCount = 0
            readySimCount = 0
        }
    }


    private fun requestGatewayWorkerStart() {
        if (gatewayId == null) {
            status = "Register this phone with the controller before starting its heartbeat service."
            return
        }
        lifecycleScope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { credentialStore.load() } }.getOrNull()
            val pinnedUrl = saved?.controllerBaseUrl
            if (pinnedUrl.isNullOrBlank() || pinnedUrl != controllerUrl.trim()) {
                status = "The saved Gateway identity is not pinned to this controller endpoint. Service startup is blocked to protect the private credential."
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                launchGatewayWorkerService()
            }
        }
    }

    private fun launchGatewayWorkerService() {
        val intent = Intent(this, GatewayForegroundService::class.java)
            .setAction(GatewayForegroundService.ACTION_START)
        workerState = "STARTING"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status = "Gateway heartbeat service requested. SMS sending and receiving remain disabled."
    }

    private fun stopGatewayWorker() {
        stopService(Intent(this, GatewayForegroundService::class.java))
        workerState = "STOPPED"
        status = "Gateway heartbeat service stopped. No SMS operation is active."
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
                controllerUrl = registered.controllerBaseUrl
                getSharedPreferences(GatewayForegroundService.PREFERENCES, MODE_PRIVATE).edit()
                    .putString(GatewayForegroundService.KEY_CONTROLLER_URL, registered.controllerBaseUrl).apply()
                status = "Gateway registration succeeded. Its private controller credential and pinned endpoint are encrypted on this phone."
            } catch (error: Exception) {
                status = "Gateway registration failed: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }
}
