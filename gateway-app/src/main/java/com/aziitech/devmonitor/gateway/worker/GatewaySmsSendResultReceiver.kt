package com.aziitech.devmonitor.gateway.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

/**
 * Persists Android's SMS_SENT result for one attempt. It never retries or
 * advances the controller queue; it only records the platform outcome.
 */
class GatewaySmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val attemptId = intent?.getStringExtra(EXTRA_ATTEMPT_ID)
            ?.takeIf(String::isNotBlank) ?: return
        runCatching {
            SqliteSmsChannelStateStore(context.applicationContext).useStore { store ->
                store.recordSendResult(attemptId, resultCode, Instant.now().toString())
            }
        }
    }

    companion object {
        const val EXTRA_ATTEMPT_ID = "gateway_sms_attempt_id"
    }
}

private inline fun <T> SqliteSmsChannelStateStore.useStore(block: (SqliteSmsChannelStateStore) -> T): T =
    try { block(this) } finally { close() }
