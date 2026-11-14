package com.aziitech.simregmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

object OperationServiceCallbacks {
    @Volatile var onStopRequested: (() -> Unit)? = null
    @Volatile var onPauseRequested: (() -> Unit)? = null
    @Volatile var onResumeRequested: (() -> Unit)? = null
}

/** Runs only for a user-started operation and removes its notification when work ends. */
class OperationForegroundService : Service() {
    private var title = "DevMonitor"
    private var detail = "Working…"
    private var progress = 0
    private var maximum = 0
    private var controls = false
    private var paused = false
    private var pauseRequested = false

    companion object {
        private const val CHANNEL = "devmonitor_active_operation"
        private const val ID = 3510
        private const val UPDATE = "com.aziitech.simregmonitor.operation.UPDATE"
        private const val STOP = "com.aziitech.simregmonitor.operation.STOP"
        private const val PAUSE = "com.aziitech.simregmonitor.operation.PAUSE"
        private const val RESUME = "com.aziitech.simregmonitor.operation.RESUME"
        private const val T = "title"; private const val D = "detail"
        private const val P = "progress"; private const val M = "maximum"
        private const val C = "controls"; private const val Q = "paused"; private const val R = "pauseRequested"

        fun start(c: Context, title: String, detail: String, progress: Int = 0, maximum: Int = 0, controls: Boolean = false) {
            ContextCompat.startForegroundService(c, intent(c, title, detail, progress, maximum, controls, false, false))
        }
        fun update(c: Context, title: String, detail: String, progress: Int = 0, maximum: Int = 0, paused: Boolean = false, pauseRequested: Boolean = false, controls: Boolean = false) {
            runCatching { c.startService(intent(c, title, detail, progress, maximum, controls, paused, pauseRequested)) }
        }
        fun stop(c: Context) { c.stopService(Intent(c, OperationForegroundService::class.java)) }
        private fun intent(c: Context, title: String, detail: String, progress: Int, maximum: Int, controls: Boolean, paused: Boolean, requested: Boolean) =
            Intent(c, OperationForegroundService::class.java).apply {
                action = UPDATE; putExtra(T, title); putExtra(D, detail); putExtra(P, progress.coerceAtLeast(0))
                putExtra(M, maximum.coerceAtLeast(0)); putExtra(C, controls); putExtra(Q, paused); putExtra(R, requested)
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            STOP -> { OperationServiceCallbacks.onStopRequested?.invoke(); stopSelf(); return START_NOT_STICKY }
            PAUSE -> { pauseRequested = true; OperationServiceCallbacks.onPauseRequested?.invoke(); detail = "Pause requested — finishing current device…" }
            RESUME -> { pauseRequested = false; paused = false; OperationServiceCallbacks.onResumeRequested?.invoke(); detail = "Resuming…" }
            else -> {
                title = i?.getStringExtra(T) ?: title; detail = i?.getStringExtra(D) ?: detail
                progress = i?.getIntExtra(P, progress) ?: progress; maximum = i?.getIntExtra(M, maximum) ?: maximum
                controls = i?.getBooleanExtra(C, controls) ?: controls; paused = i?.getBooleanExtra(Q, paused) ?: paused
                pauseRequested = i?.getBooleanExtra(R, pauseRequested) ?: pauseRequested
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "DevMonitor active operations", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(this, 3511,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (maximum > 0) b.setProgress(maximum, progress.coerceIn(0, maximum), false) else b.setProgress(0, 0, true)
        if (controls) {
            fun action(request: String, requestCode: Int) = PendingIntent.getService(this, requestCode,
                Intent(this, OperationForegroundService::class.java).setAction(request),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, if (paused || pauseRequested) "Resume" else "Pause after current", action(if (paused || pauseRequested) RESUME else PAUSE, 3513))
            b.addAction(0, "Stop", action(STOP, 3512))
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
