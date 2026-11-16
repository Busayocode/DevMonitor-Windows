package com.aziitech.devmonitor.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class GatewayPhoneReadinessState(
    val smsPermissionReady: Boolean,
    val telephonyReady: Boolean,
    val selectedSubscriptionId: Int?,
    val summary: String
)

/**
 * Reports readiness only. It never selects a SIM, sends SMS, or enables queue work.
 * The selected subscription preference is shared with MainActivity's SIM picker.
 */
object GatewayPhoneReadiness {
    const val SIM_PREFERENCES = "devmonitor_gateway_sim"
    const val KEY_SELECTED_SUBSCRIPTION_ID = "selected_subscription_id"

    fun inspect(context: Context): GatewayPhoneReadinessState {
        val sendGranted = context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val receiveGranted = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val phoneStateGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val smsReady = sendGranted && receiveGranted
        if (!phoneStateGranted) {
            return GatewayPhoneReadinessState(
                smsReady, false, null,
                "SMS permissions ${if (smsReady) "ready" else "incomplete"}; phone-state permission missing; no SIM is validated"
            )
        }

        return try {
            val prefs = context.getSharedPreferences(SIM_PREFERENCES, Context.MODE_PRIVATE)
            val selectedId = prefs.getInt(KEY_SELECTED_SUBSCRIPTION_ID, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            if (selectedId == null) {
                return GatewayPhoneReadinessState(
                    smsReady, false, null,
                    "SMS permissions ${if (smsReady) "ready" else "incomplete"}; no SIM selected"
                )
            }
            val subscriptions = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val selected = subscriptions?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == selectedId }
            if (selected == null) {
                return GatewayPhoneReadinessState(
                    smsReady, false, selectedId,
                    "SMS permissions ${if (smsReady) "ready" else "incomplete"}; selected SIM is not active"
                )
            }
            val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager?.createForSubscriptionId(selectedId)?.simState
            } else {
                @Suppress("DEPRECATION")
                manager?.simState
            }
            val ready = simState == TelephonyManager.SIM_STATE_READY
            GatewayPhoneReadinessState(
                smsReady, ready, selectedId,
                "SMS permissions ${if (smsReady) "ready" else "incomplete"}; selected SIM ${if (ready) "ready" else "not ready"}"
            )
        } catch (_: SecurityException) {
            GatewayPhoneReadinessState(
                smsReady, false, null,
                "SMS permissions ${if (smsReady) "ready" else "incomplete"}; Android denied SIM status access"
            )
        } catch (_: RuntimeException) {
            GatewayPhoneReadinessState(
                smsReady, false, null,
                "SMS permissions ${if (smsReady) "ready" else "incomplete"}; SIM status unavailable"
            )
        }
    }
}
