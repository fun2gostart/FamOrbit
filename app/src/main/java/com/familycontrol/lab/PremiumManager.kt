package com.familycontrol.lab

import android.content.Context
import android.content.SharedPreferences

object PremiumManager {
    private const val PREFS = "famorbit_premium_prefs"
    private const val KEY_IS_PREMIUM = "is_premium_active"
    private const val KEY_PLAN_TYPE = "premium_plan_type" // "ANNUAL_TRIAL", "ANNUAL", "MONTHLY"
    private const val KEY_EXPIRY_TIMESTAMP = "premium_expiry_timestamp"
    private const val KEY_TRIAL_START_TIMESTAMP = "trial_start_timestamp"

    const val PLAN_MONTHLY = "MONTHLY"
    const val PLAN_ANNUAL = "ANNUAL"

    // Default display pricing (overridden dynamically by Google Play Billing when configured)
    const val DEFAULT_MONTHLY_PRICE = "$2.99/mo"
    const val DEFAULT_ANNUAL_PRICE = "$19.99/yr"
    const val DEFAULT_ANNUAL_SAVINGS = "Save 45%"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPremium(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isExplicitPremium = prefs.getBoolean(KEY_IS_PREMIUM, false)
        if (isExplicitPremium) {
            val expiry = prefs.getLong(KEY_EXPIRY_TIMESTAMP, 0L)
            // If expiry is set and passed, revert to false (0L means lifetime / ongoing subscription)
            if (expiry > 0L && System.currentTimeMillis() > expiry) {
                setPremium(context, false)
                return false
            }
            return true
        }
        return false
    }

    fun setPremium(
        context: Context,
        active: Boolean,
        planType: String = PLAN_ANNUAL,
        durationDays: Int = 365
    ) {
        val expiry = if (active) System.currentTimeMillis() + (durationDays.toLong() * 24 * 60 * 60 * 1000) else 0L
        getPrefs(context).edit()
            .putBoolean(KEY_IS_PREMIUM, active)
            .putString(KEY_PLAN_TYPE, planType)
            .putLong(KEY_EXPIRY_TIMESTAMP, expiry)
            .apply()
        EventLog.record(context, "PREMIUM_STATUS_CHANGED active=$active plan=$planType")
    }

    fun startFreeTrial(context: Context, planType: String = PLAN_ANNUAL) {
        val now = System.currentTimeMillis()
        val trialDays = 7
        val trialExpiry = now + (trialDays.toLong() * 24 * 60 * 60 * 1000)
        getPrefs(context).edit()
            .putBoolean(KEY_IS_PREMIUM, true)
            .putString(KEY_PLAN_TYPE, "TRIAL_$planType")
            .putLong(KEY_TRIAL_START_TIMESTAMP, now)
            .putLong(KEY_EXPIRY_TIMESTAMP, trialExpiry)
            .apply()
        EventLog.record(context, "FREE_TRIAL_STARTED duration=7_days plan=$planType")
    }

    fun getPlanType(context: Context): String =
        getPrefs(context).getString(KEY_PLAN_TYPE, "FREE") ?: "FREE"

    /**
     * Entitlement limit: Free tier allows 1 child device, Premium allows unlimited.
     */
    fun maxAllowedChildren(context: Context): Int =
        if (isPremium(context)) Int.MAX_VALUE else 1

    /**
     * Check if parent can add another child under current entitlement.
     */
    fun canAddChild(context: Context, currentChildCount: Int): Boolean =
        isPremium(context) || currentChildCount < maxAllowedChildren(context)
}
