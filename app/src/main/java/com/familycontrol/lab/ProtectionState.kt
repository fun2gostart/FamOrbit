package com.familycontrol.lab

import android.content.Context

enum class ProtectionState {
    PROTECTED,
    DEGRADED,
    INTERRUPTED,
    OFFLINE,
    OUTDATED_POLICY
}

data class ProtectionSnapshot(
    val state: ProtectionState,
    val policyVersion: Int,
    val lastHeartbeatMinutes: Long?,
    val networkAvailable: Boolean,
    val usageAccess: Boolean,
    val clockChanged: Boolean,
    val detail: String
)

object ProtectionMonitor {
    private const val PREFS = "protection"

    fun policyVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("policy_version", 1)

    fun incrementPolicyVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt("policy_version", 1) + 1
        prefs.edit().putInt("policy_version", next).apply()
        EventLog.record(context, "POLICY_VERSION_CHANGED v$next")
        return next
    }

    fun markClockChange(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("clock_changed", true).apply()
        EventLog.record(context, "CLOCK_CHANGE_DETECTED")
    }

    fun clearClockAlert(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("clock_changed", false).apply()
        EventLog.record(context, "CLOCK_ALERT_CLEARED")
    }

    fun snapshot(context: Context): ProtectionSnapshot {
        val heartbeat = context.getSharedPreferences("heartbeat", Context.MODE_PRIVATE)
        val protection = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val last = heartbeat.getLong("last_heartbeat", 0L)
        val age = if (last == 0L) null
        else ((System.currentTimeMillis() - last) / 60_000L).coerceAtLeast(0L)

        val network = heartbeat.getBoolean("network_available", true)
        val usage = hasUsageAccess(context)
        val clockChanged = protection.getBoolean("clock_changed", false)
        val version = policyVersion(context)

        val state = when {
            clockChanged -> ProtectionState.INTERRUPTED
            !network && age != null && age > 20 -> ProtectionState.OFFLINE
            !usage -> ProtectionState.DEGRADED
            age == null || age > 20 -> ProtectionState.DEGRADED
            else -> ProtectionState.PROTECTED
        }

        val detail = when (state) {
            ProtectionState.PROTECTED -> "Core monitoring signals are healthy"
            ProtectionState.DEGRADED -> "A required protection signal needs attention"
            ProtectionState.INTERRUPTED -> "A clock/timezone change was detected"
            ProtectionState.OFFLINE -> "Device is offline; local policies remain available"
            ProtectionState.OUTDATED_POLICY -> "Local policy version needs synchronization"
        }

        return ProtectionSnapshot(
            state = state,
            policyVersion = version,
            lastHeartbeatMinutes = age,
            networkAvailable = network,
            usageAccess = usage,
            clockChanged = clockChanged,
            detail = detail
        )
    }
}
