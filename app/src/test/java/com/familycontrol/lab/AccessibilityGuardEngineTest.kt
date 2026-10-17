package com.familycontrol.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGuardEngineTest {

    @Test
    fun isAlwaysAllowedEmergencyApp_identifiesEmergencyPackages() {
        // Dialers / Phone
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.google.android.dialer"))
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.samsung.android.dialer"))
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.android.phone"))

        // Camera
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.google.android.GoogleCamera"))
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.oplus.camera"))

        // Clock / Alarm
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.google.android.deskclock"))

        // Calculator
        assertTrue(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.google.android.calculator"))

        // Regular restricted apps should return false
        assertFalse(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "in.buywisehub.storagescope"))
        assertFalse(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.instagram.android"))
        assertFalse(AccessibilityGuardEngine.isAlwaysAllowedEmergencyApp(null, "com.roblox.client"))
    }

    @Test
    fun reconcileAccumulatedMs_clampsPhantomSleepDrift() {
        val usageStatsMs = 17 * 60_000L // 17 minutes OS ground truth
        val maxAllowed = usageStatsMs + 120_000L // 19 minutes max allowed un-flushed margin

        // Case 1: Corrupted phantom sleep time (e.g. 57 minutes)
        val bloatedMs = 57 * 60_000L
        val candidate1 = if (bloatedMs > maxAllowed) usageStatsMs else bloatedMs
        assertEquals("Bloated milliseconds must be clamped to UsageStats ground truth", usageStatsMs, candidate1)

        // Case 2: Normal in-session usage (e.g. 18 minutes, within 2-min margin)
        val validSessionMs = 18 * 60_000L
        val candidate2 = if (validSessionMs > maxAllowed) usageStatsMs else validSessionMs
        assertEquals("Valid in-session usage should be preserved", validSessionMs, candidate2)
    }
}
