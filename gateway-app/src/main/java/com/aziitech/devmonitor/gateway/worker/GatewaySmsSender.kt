package com.aziitech.devmonitor.gateway.worker

import android.Manifest
import android.content.Context
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

sealed interface GatewaySmsDispatchResult {
    data object SubmittedToAndroid : GatewaySmsDispatchResult
    data class Blocked(val reason: String) : GatewaySmsDispatchResult
}

/**
 * Subscription-pinned 3030 sender primitive.
 *
 * This is deliberately not connected to the heartbeat service or queue yet.
 * Callers must first receive controller acceptance for the SENDING transition,
 * provide a sent-result PendingIntent, and pass the exact SIM selected by the
 * operator/automatic-Glo selector. The selected SIM is revalidated immediately
 * before SmsManager is obtained and the SMS is submitted.
 */
class GatewaySmsSender(private val context: Context) {
    fun sendQuery(
        selectedSubscriptionId: Int?,
        canonicalDeviceId: String,
        sentResultIntent: PendingIntent?,
        controllerAcceptedSending: Boolean
    ): GatewaySmsDispatchResult {
        if (!controllerAcceptedSending) {
            return GatewaySmsDispatchResult.Blocked("Controller has not accepted the SENDING transition")
        }
        if (selectedSubscriptionId == null || selectedSubscriptionId < 0) {
            return GatewaySmsDispatchResult.Blocked("No explicit Gateway SIM is selected")
        }
        if (canonicalDeviceId.isBlank() || canonicalDeviceId.length > 40 ||
            !canonicalDeviceId.matches(Regex("""[A-Za-z0-9-]+"""))
        ) {
            return GatewaySmsDispatchResult.Blocked("Device query code is invalid")
        }
        if (sentResultIntent == null) {
            return GatewaySmsDispatchResult.Blocked("A send-result callback is required")
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            return GatewaySmsDispatchResult.Blocked("SMS send and phone-state permissions are required")
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return GatewaySmsDispatchResult.Blocked("Subscription service is unavailable")
        val active = runCatching { subscriptionManager.activeSubscriptionInfoList.orEmpty() }
            .getOrElse { return GatewaySmsDispatchResult.Blocked("Active SIM list could not be read") }
        if (active.none { it.subscriptionId == selectedSubscriptionId }) {
            return GatewaySmsDispatchResult.Blocked("The selected SIM is no longer active; no fallback SIM will be used")
        }

        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return GatewaySmsDispatchResult.Blocked("Telephony service is unavailable")
        val simState = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephony.createForSubscriptionId(selectedSubscriptionId).simState
            } else {
                telephony.simState
            }
        }.getOrElse { return GatewaySmsDispatchResult.Blocked("Selected SIM readiness could not be verified") }
        if (simState != TelephonyManager.SIM_STATE_READY) {
            return GatewaySmsDispatchResult.Blocked("Selected SIM is not READY; no SMS was sent")
        }

        // Recheck active subscription membership and READY state immediately
        // before constructing the subscription-bound SmsManager and sending.
        val stillActive = runCatching {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
                .any { it.subscriptionId == selectedSubscriptionId }
        }.getOrDefault(false)
        val stillReady = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephony.createForSubscriptionId(selectedSubscriptionId).simState == TelephonyManager.SIM_STATE_READY
            } else telephony.simState == TelephonyManager.SIM_STATE_READY
        }.getOrDefault(false)
        if (!stillActive || !stillReady) {
            return GatewaySmsDispatchResult.Blocked("Selected SIM changed or stopped being READY immediately before send")
        }

        val manager = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SmsManager.createForSubscriptionId(selectedSubscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(selectedSubscriptionId)
            }
        }.getOrElse {
            return GatewaySmsDispatchResult.Blocked("Could not create an SMS manager for the selected SIM")
        }

        return runCatching {
            manager.sendTextMessage("3030", null, canonicalDeviceId, sentResultIntent, null)
            GatewaySmsDispatchResult.SubmittedToAndroid
        }.getOrElse {
            GatewaySmsDispatchResult.Blocked("Android rejected the SMS submission: ${it.javaClass.simpleName}")
        }
    }
}
