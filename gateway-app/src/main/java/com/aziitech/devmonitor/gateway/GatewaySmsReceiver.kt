package com.aziitech.devmonitor.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.SmsMessage
import com.aziitech.devmonitor.gateway.worker.GatewayInboundSmsStore
import com.aziitech.devmonitor.gateway.worker.SmsSenderPolicy

/**
 * Receives Android SMS broadcasts but stores only messages from the exact 3030
 * service short code. It deliberately does not parse, assign, retry or submit
 * results; those actions require the transaction gate and worker orchestration.
 */
class GatewaySmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull()?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return

        val groups = messages.groupBy { it.originatingAddress.orEmpty() }
        val subscriptionId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        ).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID && it >= 0 }

        val store = runCatching { GatewayInboundSmsStore(context) }.getOrNull() ?: return
        try {
            groups.forEach { (sender, parts) ->
                if (!SmsSenderPolicy.is3030(sender)) return@forEach
                val ordered = parts.sortedBy { it.timestampMillis }
                val body = ordered.joinToString(separator = "") { it.messageBody.orEmpty() }
                if (body.isBlank()) return@forEach
                val receivedAt = ordered.maxOfOrNull(SmsMessage::getTimestampMillis)
                    ?: System.currentTimeMillis()
                runCatching {
                    store.record3030(sender, receivedAt, subscriptionId, body)
                }
            }
        } finally {
            store.close()
        }
    }
}
