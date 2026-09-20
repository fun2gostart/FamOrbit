package com.familycontrol.lab

import android.content.Context

object PiggyBankEngine {
    private const val PREFS = "piggy_bank_v1"
    private const val KEY_BALANCE = "saved_minutes"

    fun getBalanceMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BALANCE)) {
            // Default 45 mins initial savings balance for demonstration
            prefs.edit().putInt(KEY_BALANCE, 45).apply()
            return 45
        }
        return prefs.getInt(KEY_BALANCE, 0)
    }

    fun addRolloverMinutes(context: Context, minutes: Int) {
        val current = getBalanceMinutes(context)
        val updated = current + minutes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BALANCE, updated).apply()
        EventLog.record(context, "PIGGY_BANK_DEPOSIT +${minutes}m (New Balance: ${updated}m)")
    }

    fun redeemMinutes(context: Context, minutes: Int): Boolean {
        val current = getBalanceMinutes(context)
        if (current < minutes) return false
        val updated = current - minutes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BALANCE, updated).apply()
        EventLog.record(context, "PIGGY_BANK_REDEEM -${minutes}m (Remaining: ${updated}m)")
        return true
    }
}
