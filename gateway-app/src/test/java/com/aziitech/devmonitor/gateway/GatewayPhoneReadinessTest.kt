package com.aziitech.devmonitor.gateway

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewayPhoneReadinessTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun missingPermissionsNeverReportsSmsOrTelephonyReady() {
        val state = GatewayPhoneReadiness.inspect(context)
        assertFalse(state.smsPermissionReady)
        assertFalse(state.telephonyReady)
        assertNull(state.selectedSubscriptionId)
        assertTrue(state.summary.contains("permission"))
    }

    @Test
    fun grantedSmsPermissionsWithoutSelectedSimStillBlocksTelephonyReadiness() {
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        context.getSharedPreferences(GatewayPhoneReadiness.SIM_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(GatewayPhoneReadiness.KEY_SELECTED_SUBSCRIPTION_ID).commit()

        val state = GatewayPhoneReadiness.inspect(context)
        assertTrue(state.smsPermissionReady)
        assertFalse(state.telephonyReady)
        assertNull(state.selectedSubscriptionId)
        assertTrue(state.summary.contains("no SIM selected"))
    }

    @Test
    fun selectedButInactiveSimIsNotReportedReady() {
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        context.getSharedPreferences(GatewayPhoneReadiness.SIM_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(GatewayPhoneReadiness.KEY_SELECTED_SUBSCRIPTION_ID, 987654).commit()

        val state = GatewayPhoneReadiness.inspect(context)
        assertTrue(state.smsPermissionReady)
        assertFalse(state.telephonyReady)
        assertTrue(state.summary.contains("not active"))
    }
}
