package com.aziitech.devmonitor.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import com.aziitech.devmonitor.gateway.worker.AndroidGatewayCredentialStore
import com.aziitech.devmonitor.gateway.worker.AndroidGatewayLeaseTokenStore
import com.aziitech.devmonitor.gateway.worker.GatewayAttemptState
import com.aziitech.devmonitor.gateway.worker.GatewayCancellationPolicy
import com.aziitech.devmonitor.gateway.worker.GatewayCancellationSnapshot
import com.aziitech.devmonitor.gateway.worker.Gateway3030InboxProcessor
import com.aziitech.devmonitor.gateway.worker.GatewayDispatchRecoveryAdapter
import com.aziitech.devmonitor.gateway.worker.GatewayRecoveryStatus
import com.aziitech.devmonitor.gateway.worker.GatewayInboundSmsStore
import com.aziitech.devmonitor.gateway.worker.GatewaySmsTransactionGate
import java.time.Duration
import java.time.Instant
import com.aziitech.devmonitor.gateway.worker.GatewaySmsDispatchCoordinator
import com.aziitech.devmonitor.gateway.worker.SqliteSmsChannelStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-started Gateway heartbeat service foundation.
 *
 * The user-started service reports readiness, reconciles durable attempts,
 * processes only exactly correlated 3030 replies, and may claim one controller
 * lease when the phone is ready. Outbound SMS dispatch remains disabled.
 */
class GatewayForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private lateinit var credentials: AndroidGatewayCredentialStore

    override fun onCreate() {
        super.onCreate()
        credentials = AndroidGatewayCredentialStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START, ACTION_RESUME -> {
                enterForeground()
                startHeartbeatLoop()
            }
            ACTION_PAUSE -> {
                enterForeground()
                heartbeatJob?.cancel()
                heartbeatJob = null
                saveState(STATE_PAUSED)
                updateNotification("Paused by operator. No SMS activity is enabled.")
            }
            ACTION_STOP -> stopWorker()
            ACTION_CANCEL_ACTIVE -> {
                enterForeground()
                serviceScope.launch { cancelActiveAttempt() }
            }
            else -> {
                enterForeground()
                updateNotification("Unknown command. Heartbeat is not running.")
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val notification = buildNotification("Starting controller heartbeat…", paused = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = serviceScope.launch {
            val prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val baseUrl = prefs.getString(KEY_CONTROLLER_URL, "").orEmpty().trim()
            val credential = runCatching { credentials.load() }.getOrNull()
            if (!baseUrl.startsWith("https://", ignoreCase = true) || credential == null || credential.controllerBaseUrl != baseUrl) {
                saveState("SETUP_REQUIRED")
                updateNotification("Setup required. Register this phone and configure HTTPS.")
                stopSelf()
                return@launch
            }

            saveState(STATE_RUNNING)
            while (isActive) {
                try {
                    val client = GatewayWorkerControllerClient(
                        baseUrl = baseUrl,
                        gatewayTokenProvider = {
                            credentials.load()?.gatewayToken
                                ?: throw IllegalStateException("Gateway credential is unavailable")
                        }
                    )
                    val readiness = GatewayPhoneReadiness.inspect(applicationContext)
                    val networkReady = hasValidatedNetwork()
                    val heartbeat = client.heartbeat(
                        appVersion = BuildConfig.VERSION_NAME,
                        smsPermissionReady = readiness.smsPermissionReady,
                        telephonyReady = readiness.telephonyReady,
                        networkReady = networkReady
                    )
                    val callbackNote = reconcileDurableSendCallback(client)
                    val replyNote = processDurableReplyInbox(client)
                    val claimNote = claimControllerWorkIfIdle(client, readiness, networkReady)
                    saveState(STATE_RUNNING)
                    updateNotification(
                        "Controller heartbeat acknowledged (${heartbeat.healthState}). " +
                            readiness.summary + ". " +
                            (claimNote ?: replyNote ?: callbackNote ?: "No active work. SMS sending remains disabled.")
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    saveState(STATE_RUNNING)
                    updateNotification(
                        "Controller unavailable; retrying. SMS processing is disabled."
                    )
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }


    /**
     * Restore controller/local attempt state before processing the durable inbox.
     * This submits only a reply correlated to an existing AWAITING_REPLY attempt.
     * It never claims work, sends SMS, retries, or advances the queue.
     */
    private suspend fun processDurableReplyInbox(
        controller: GatewayWorkerControllerClient
    ): String? {
        val ledger = SqliteSmsChannelStateStore(applicationContext)
        val leaseTokens = AndroidGatewayLeaseTokenStore(applicationContext)
        val inbox = GatewayInboundSmsStore(applicationContext)
        val gate = GatewaySmsTransactionGate(ledger, Duration.ZERO)
        try {
            val recovery = GatewayDispatchRecoveryAdapter().reconcile(
                controller = controller,
                ledger = ledger,
                leaseTokens = leaseTokens,
                gate = gate
            )
            return when (recovery.status) {
                GatewayRecoveryStatus.RESUME_WAITING_FOR_REPLY -> {
                    val processor = Gateway3030InboxProcessor(inbox, gate)
                    val intake = processor.processUnassigned()
                    val submitted = processor.submitAcceptedPending(
                        controller = controller,
                        ledger = ledger,
                        leaseTokens = leaseTokens
                    )
                    when {
                        submitted > 0 -> "Submitted " + submitted + " verified 3030 result(s). No post-reply wait; SMS dispatch remains disabled."
                        intake.acceptedPendingController > 0 -> "Verified 3030 reply durably staged; guarded controller submission is pending."
                        intake.unassigned > 0 || intake.duplicates > 0 -> "3030 inbox reviewed: " + intake.unassigned + " unassigned, " + intake.duplicates + " duplicate."
                        else -> "Recovered the exact active attempt and resumed 3030 reply intake. No resend."
                    }
                }
                GatewayRecoveryStatus.NO_ACTIVE_WORK -> "No active controller attempt. Automatic claim/send remains disabled."
                GatewayRecoveryStatus.ELIGIBLE_LEASED_WORK -> "Controller lease found, but automatic claim/send remains disabled."
                GatewayRecoveryStatus.WAITING_FOR_DRAIN_WINDOW -> "Waiting for the configured channel interval."
                GatewayRecoveryStatus.WAITING_FOR_CANCELLATION_COOLDOWN -> cooldownStatusText(recovery.cooldownUntil)
                GatewayRecoveryStatus.QUARANTINED -> "Gateway channel quarantined: " + recovery.reason
            }
        } finally {
            inbox.close()
            ledger.close()
        }
    }
    /**
     * Claim at most one controller-authorised job after a fresh recovery check.
     * This persists the lease and attempt locally but deliberately does not
     * transition to SENDING or invoke GatewaySmsDispatchCoordinator.
     */
    private suspend fun claimControllerWorkIfIdle(
        controller: GatewayWorkerControllerClient,
        readiness: GatewayPhoneReadinessState,
        networkReady: Boolean
    ): String? {
        val stored = runCatching { credentials.load() }.getOrNull()
            ?: return "Gateway identity unavailable; no job was claimed."
        val ledger = SqliteSmsChannelStateStore(applicationContext)
        val leaseTokens = AndroidGatewayLeaseTokenStore(applicationContext)
        val gate = GatewaySmsTransactionGate(ledger, Duration.ZERO)
        try {
            val recovery = GatewayDispatchRecoveryAdapter().reconcile(
                controller = controller,
                ledger = ledger,
                leaseTokens = leaseTokens,
                gate = gate
            )
            if (recovery.status != GatewayRecoveryStatus.NO_ACTIVE_WORK) {
                return when (recovery.status) {
                    GatewayRecoveryStatus.ELIGIBLE_LEASED_WORK ->
                        "One controller lease is already persisted. Dispatch remains disabled."
                    GatewayRecoveryStatus.RESUME_WAITING_FOR_REPLY ->
                        "An existing attempt is waiting for its 3030 reply. No new job was claimed."
                    GatewayRecoveryStatus.WAITING_FOR_DRAIN_WINDOW ->
                        "The SMS channel is not idle. No new job was claimed."
                    GatewayRecoveryStatus.WAITING_FOR_CANCELLATION_COOLDOWN ->
                        cooldownStatusText(recovery.cooldownUntil)
                    GatewayRecoveryStatus.QUARANTINED ->
                        "Gateway channel quarantined: " + recovery.reason
                    GatewayRecoveryStatus.NO_ACTIVE_WORK -> null
                }
            }

            if (!readiness.smsPermissionReady || !readiness.telephonyReady || !networkReady) {
                return "Job claim paused until SMS permissions, selected SIM readiness and validated network are ready."
            }

            val claimed = controller.claimAndPersist(
                gatewayId = stored.gatewayId,
                ledger = ledger,
                leaseTokens = leaseTokens
            ) ?: return "No queued job is currently available. SMS sending remains disabled."

            return "Claimed and securely persisted one job (" + claimed.jobId +
                "). It is not sent; waiting for the next guarded dispatch stage."
        } finally {
            ledger.close()
        }
    }

    /**
     * Reconcile only an already-persisted Android SMS_SENT callback.
     *
     * This never claims work, sends/retries SMS, or treats a received 3030
     * message as a result. A callback is replayed only when the controller's
     * single active attempt exactly matches the durable local attempt.
     */
    private suspend fun reconcileDurableSendCallback(
        controller: GatewayWorkerControllerClient
    ): String? {
        val ledger = SqliteSmsChannelStateStore(applicationContext)
        val leaseTokens = AndroidGatewayLeaseTokenStore(applicationContext)
        return try {
            val unresolved = ledger.loadUnresolvedDispatches()
            if (unresolved.size != 1) return null
            val local = unresolved.single()
            if (local.attempt.state !in setOf(
                    GatewayAttemptState.AWAITING_REPLY,
                    GatewayAttemptState.SEND_FAILED
                )
            ) return null

            val server = controller.fetchActiveAttempt(leaseTokens) ?: return null
            if (server.jobId != local.job.jobId ||
                server.attemptId != local.attempt.attemptId ||
                server.state != "SENDING"
            ) return null

            GatewaySmsDispatchCoordinator(applicationContext).reconcileSendCallback(
                jobId = local.job.jobId,
                attemptId = local.attempt.attemptId,
                controller = controller,
                ledger = ledger,
                leaseTokens = leaseTokens
            )
            when (local.attempt.state) {
                GatewayAttemptState.AWAITING_REPLY ->
                    "A previously recorded successful Android send callback was reconciled. Waiting for the matching 3030 reply; no resend."
                GatewayAttemptState.SEND_FAILED ->
                    "Android reported that the SMS send failed. The attempt was marked for manual reconciliation; no retry."
                else -> null
            }
        } finally {
            ledger.close()
        }
    }


    /**
     * Operator cancellation is fail-closed. The controller's exact active attempt,
     * the one unresolved SQLite pair and the encrypted lease token must all agree.
     * Any transport uncertainty quarantines locally; it is never reported as success.
     */
    private suspend fun cancelActiveAttempt() {
        val wasRunning = heartbeatJob?.isActive == true
        heartbeatJob?.cancelAndJoin()
        val prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_CONTROLLER_URL, "").orEmpty().trim()
        val credential = runCatching { credentials.load() }.getOrNull()
        if (!baseUrl.startsWith("https://", ignoreCase = true) ||
            credential == null || credential.controllerBaseUrl != baseUrl
        ) {
            publishWorkerMessage("Cancellation unavailable: this Gateway has no valid pinned HTTPS controller identity.")
            if (wasRunning) startHeartbeatLoop()
            return
        }

        val ledger = SqliteSmsChannelStateStore(applicationContext)
        val leaseTokens = AndroidGatewayLeaseTokenStore(applicationContext)
        val gate = GatewaySmsTransactionGate(ledger, Duration.ZERO)
        try {
            publishWorkerMessage("Checking the exact active controller attempt before cancellation…")
            val controller = GatewayWorkerControllerClient(
                baseUrl = baseUrl,
                gatewayTokenProvider = {
                    credentials.load()?.gatewayToken
                        ?: throw IllegalStateException("Gateway credential is unavailable")
                }
            )
            val server = controller.fetchActiveAttempt(leaseTokens)
            val unresolved = ledger.loadUnresolvedDispatches()
            if (server == null) {
                publishWorkerMessage("There is no active controller attempt to cancel.")
                return
            }
            if (unresolved.size != 1) {
                gate.quarantine("Operator cancellation found zero or multiple local unresolved attempts.")
                publishWorkerMessage("Cancellation blocked: local attempt records do not identify exactly one active job. Gateway is quarantined for reconciliation.")
                return
            }
            val local = unresolved.single()
            if (server.jobId != local.job.jobId ||
                server.attemptId != local.attempt.attemptId ||
                local.job.gatewayId != credential.gatewayId ||
                local.attempt.gatewayId != credential.gatewayId ||
                !server.localLeaseTokenAvailable
            ) {
                gate.quarantine("Operator cancellation could not prove exact controller, local job, attempt, Gateway and lease-token identity.")
                publishWorkerMessage("Cancellation blocked: controller, local record or secure lease token mismatch. No job was released; Gateway is quarantined.")
                return
            }

            val decision = GatewayCancellationPolicy.assess(
                GatewayCancellationSnapshot(
                    controllerState = server.state,
                    localJobState = local.job.state.name,
                    localAttemptState = local.attempt.state.name,
                    transactionId = local.attempt.transactionId,
                    sendStartedAt = local.attempt.sendStartedAt,
                    sentAt = local.attempt.sentAt
                )
            )
            publishWorkerMessage(
                when (decision) {
                    com.aziitech.devmonitor.gateway.worker.GatewayCancellationDecision.CANCEL_BEFORE_SEND ->
                        "Cancelling the exact leased job before SMS submission…"
                    com.aziitech.devmonitor.gateway.worker.GatewayCancellationDecision.REQUIRE_CONTROLLER_CLOSURE_AND_COOLDOWN ->
                        "Requesting controller closure of the confirmed-sent attempt and its four-minute cooldown…"
                    com.aziitech.devmonitor.gateway.worker.GatewayCancellationDecision.BLOCK_AND_QUARANTINE ->
                        "Attempt state is uncertain. Asking the controller to quarantine it; it will not be released."
                }
            )
            val result = controller.cancelAttempt(
                jobId = server.jobId,
                attemptId = server.attemptId,
                reason = "Operator requested cancellation from Gateway phone",
                ledger = ledger,
                leaseTokens = leaseTokens,
                transactionGate = gate
            )
            val message = when (result.outcome) {
                "CANCELLED_BEFORE_SEND" ->
                    "Job cancelled before SMS submission. The controller confirmed closure; no SMS was sent."
                "CANCELLED_AFTER_SEND" ->
                    "Confirmed-sent attempt closed. Cooldown · four minutes from the controller deadline; no new query until it expires."
                "BLOCKED_QUARANTINED" ->
                    "Cancellation was blocked because the attempt was uncertain. Controller and local channel are quarantined; operator reconciliation is required."
                else -> "Controller returned an unrecognised cancellation outcome. Treat this attempt as unresolved."
            }
            publishWorkerMessage(message)
        } catch (failure: Exception) {
            gate.quarantine("Operator cancellation outcome is uncertain: " + (failure.message ?: "controller request failed"))
            publishWorkerMessage("Cancellation not confirmed. The channel has been quarantined because the controller response may be uncertain. Do not retry or assume the job was cancelled.")
        } finally {
            ledger.close()
            if (wasRunning) startHeartbeatLoop()
        }
    }

    private fun publishWorkerMessage(message: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(KEY_WORKER_MESSAGE, message)
            .apply()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_STATE, readState(this))
            .putExtra(EXTRA_STATUS, message))
        updateNotification(message)
    }

    private fun cooldownStatusText(deadline: Instant?): String {
        val remainingSeconds = deadline?.let {
            Duration.between(Instant.now(), it).seconds.coerceAtLeast(0)
        } ?: 0L
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return "Cooldown · %d:%02d remaining. No new 3030 query will start until it expires."
            .format(minutes, seconds)
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun stopWorker() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        saveState(STATE_STOPPED)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun saveState(state: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(KEY_WORKER_STATE, state)
            .apply()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_STATE, state))
    }

    private fun updateNotification(message: String) {
        val paused = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_WORKER_STATE, STATE_STOPPED) == STATE_PAUSED
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message, paused))
    }

    private fun buildNotification(message: String, paused: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseOrResumeAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseOrResumeLabel = if (paused) "Resume" else "Pause"
        return notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DevMonitor Gateway")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                pauseOrResumeLabel,
                servicePendingIntent(pauseOrResumeAction, REQUEST_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                servicePendingIntent(ACTION_STOP, REQUEST_STOP)
            )
            .build()
    }

    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, GatewayForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gateway worker status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status and operator controls for the Gateway phone."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        saveState(STATE_STOPPED)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aziitech.devmonitor.gateway.action.START"
        const val ACTION_PAUSE = "com.aziitech.devmonitor.gateway.action.PAUSE"
        const val ACTION_RESUME = "com.aziitech.devmonitor.gateway.action.RESUME"
        const val ACTION_STOP = "com.aziitech.devmonitor.gateway.action.STOP"
        const val ACTION_CANCEL_ACTIVE = "com.aziitech.devmonitor.gateway.action.CANCEL_ACTIVE"
        const val ACTION_STATE_CHANGED = "com.aziitech.devmonitor.gateway.action.STATE_CHANGED"
        const val EXTRA_STATE = "worker_state"
        const val EXTRA_STATUS = "worker_status"

        const val PREFERENCES = "devmonitor_gateway_runtime"
        const val KEY_CONTROLLER_URL = "controller_base_url"
        const val KEY_WORKER_MESSAGE = "worker_message"
        private const val KEY_WORKER_STATE = "worker_state"
        private const val CHANNEL_ID = "gateway_worker_status"
        private const val NOTIFICATION_ID = 5300
        private const val REQUEST_OPEN_APP = 5301
        private const val REQUEST_TOGGLE = 5302
        private const val REQUEST_STOP = 5303
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_STOPPED = "STOPPED"

        fun readState(context: Context): String {
            val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_WORKER_STATE, STATE_STOPPED) ?: STATE_STOPPED
            if (saved == STATE_RUNNING || saved == STATE_PAUSED) {
                @Suppress("DEPRECATION")
                val running = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                    .getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == GatewayForegroundService::class.java.name }
                if (!running) {
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(KEY_WORKER_STATE, STATE_STOPPED).apply()
                    return STATE_STOPPED
                }
            }
            return saved
        }
    }
}
