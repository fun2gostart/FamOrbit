package com.familycontrol.lab

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object PresetModeEngine {
    private const val PREFS = "preset_mode_prefs"
    private const val KEY_MODE = "active_preset_mode"
    private const val KEY_EXPIRES_AT = "preset_expires_at"
    private const val KEY_REQ_DEACTIVATE = "deactivate_requested"
    private const val KEY_REQ_REASON = "deactivate_reason"

    const val MODE_NONE = "NONE"
    const val MODE_STUDY = "STUDY_FOCUS"
    const val MODE_BEDTIME = "BEDTIME_LOCK"
    const val MODE_DINNER = "DINNER_LOCK"
    const val MODE_REWARD = "REWARD_BOOST"

    fun getActivePreset(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
        if (mode == MODE_NONE) return MODE_NONE

        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) {
            // Preset expired automatically after designated duration
            clearPresetInternal(context)
            return MODE_NONE
        }
        return mode
    }

    fun getRemainingMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return 0
        val remainingMs = expiresAt - System.currentTimeMillis()
        return (remainingMs / 60_000L).coerceAtLeast(0L).toInt()
    }

    fun setActivePreset(context: Context, mode: String, durationHours: Int = 1, durationMinutes: Int = 0) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val totalMs = if (durationMinutes > 0) {
            durationMinutes * 60_000L
        } else {
            durationHours.coerceAtLeast(1) * 3600_000L
        }
        val expiresAt = System.currentTimeMillis() + totalMs

        prefs.edit()
            .putString(KEY_MODE, mode)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putBoolean(KEY_REQ_DEACTIVATE, false)
            .remove(KEY_REQ_REASON)
            .apply()

        EventLog.record(context, "PRESET_MODE_CHANGED $mode durationMs=$totalMs")
        triggerEnforcement(context)
    }

    fun deactivateWithPin(context: Context, pin: String): Boolean {
        if (!ParentSecurity.verifyPin(context, pin)) {
            return false
        }
        clearPresetInternal(context)
        return true
    }

    fun forceDeactivateByParent(context: Context) {
        clearPresetInternal(context)
    }

    private fun clearPresetInternal(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, MODE_NONE)
            .putLong(KEY_EXPIRES_AT, 0L)
            .putBoolean(KEY_REQ_DEACTIVATE, false)
            .remove(KEY_REQ_REASON)
            .apply()

        EventLog.record(context, "PRESET_MODE_DEACTIVATED")
        triggerEnforcement(context)
    }

    fun requestDeactivation(context: Context, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_REQ_DEACTIVATE, true)
            .putString(KEY_REQ_REASON, reason.ifBlank { "Child requested parent to end preset mode" })
            .apply()

        EventLog.record(context, "PRESET_DEACTIVATION_REQUESTED $reason")
        NotificationEngine.notify(
            context,
            99988,
            "Preset Deactivation Request",
            "Child requested parent to deactivate current preset mode: $reason"
        )
    }

    fun isDeactivationRequested(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REQ_DEACTIVATE, false)
    }

    fun getDeactivationReason(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_REQ_REASON, "Child requested parent to end preset mode") ?: ""
    }

    fun clearDeactivationRequest(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REQ_DEACTIVATE, false).remove(KEY_REQ_REASON).apply()
    }

    private fun triggerEnforcement(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EnforcementService::class.java)
            )
        } catch (_: Exception) {}
    }

    fun getPresetLabel(mode: String): String {
        return when (mode) {
            MODE_STUDY -> "🎓 Study & Focus Mode"
            MODE_BEDTIME -> "🌙 Instant Bedtime Lock"
            MODE_DINNER -> "🍽️ Dinner Time Lock"
            MODE_REWARD -> "🎁 Bonus Reward Time"
            else -> "Standard Mode"
        }
    }

    fun getPresetDescription(mode: String): String {
        return when (mode) {
            MODE_STUDY -> "Social, Gaming & Entertainment apps restricted. Education and productivity remain open."
            MODE_BEDTIME -> "All non-essential apps locked for bedtime."
            MODE_DINNER -> "All entertainment, gaming & social apps locked for family dinner."
            MODE_REWARD -> "+15 mins bonus time added across allowed apps."
            else -> "Default daily time limits active."
        }
    }
}
