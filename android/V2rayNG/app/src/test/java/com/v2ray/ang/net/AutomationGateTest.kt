package com.v2ray.ang.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationGateTest {
    private val key = "0123456789abcdef0123"

    @Test
    fun onlyWithTheSwitchOnAndTheRightKey() {
        assertTrue(AutomationGate.allowed(true, key, key))
        assertFalse(AutomationGate.allowed(false, key, key))
        assertFalse(AutomationGate.allowed(true, key, null))
        assertFalse(AutomationGate.allowed(true, key, "wrong"))
        assertFalse(AutomationGate.allowed(true, null, null))
        assertFalse(AutomationGate.allowed(true, "", ""))
        assertFalse(AutomationGate.allowed(true, "short", "short"))
    }
}
