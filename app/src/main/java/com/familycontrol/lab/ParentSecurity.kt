package com.familycontrol.lab

import android.content.Context

object ParentSecurity {
    private const val PREFS = "parent_security_prefs"
    private const val KEY_PIN = "parent_pin"

    fun getPin(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN, "1234") ?: "1234"

    fun setPin(context: Context, newPin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIN, newPin.trim()).apply()
        EventLog.record(context, "PARENT_PIN_UPDATED")
    }

    fun verifyPin(context: Context, inputPin: String): Boolean =
        inputPin.trim() == getPin(context)
}
