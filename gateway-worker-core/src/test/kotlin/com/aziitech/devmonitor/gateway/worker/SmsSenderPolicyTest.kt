package com.aziitech.devmonitor.gateway.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderPolicyTest {
    @Test fun acceptsOnlyTheComplete3030ShortCode() {
        assertTrue(SmsSenderPolicy.is3030("3030"))
        assertTrue(SmsSenderPolicy.is3030("+3030"))
        assertTrue(SmsSenderPolicy.is3030("30-30"))
        assertFalse(SmsSenderPolicy.is3030("30300"))
        assertFalse(SmsSenderPolicy.is3030("13030"))
        assertFalse(SmsSenderPolicy.is3030("+2348030303030"))
        assertFalse(SmsSenderPolicy.is3030("GLO"))
        assertFalse(SmsSenderPolicy.is3030(null))
        assertFalse(SmsSenderPolicy.is3030(" "))
    }
}
