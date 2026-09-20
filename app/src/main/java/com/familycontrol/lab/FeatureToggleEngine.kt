package com.familycontrol.lab

import android.content.Context

object FeatureToggleEngine {
    private const val PREFS = "feature_toggles_v1"
    private const val KEY_CATEGORY_BUDGETS = "toggle_category_budgets"
    private const val KEY_PIGGY_BANK = "toggle_piggy_bank"
    private const val KEY_HABIT_BADGES = "toggle_habit_badges"
    private const val KEY_EXECUTIVE_REPORT = "toggle_executive_report"

    fun isCategoryBudgetsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CATEGORY_BUDGETS, true)
    }

    fun setCategoryBudgetsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CATEGORY_BUDGETS, enabled).apply()
        EventLog.record(context, "FEATURE_TOGGLE CategoryBudgets=$enabled")
    }

    fun isPiggyBankEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PIGGY_BANK, true)
    }

    fun setPiggyBankEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PIGGY_BANK, enabled).apply()
        EventLog.record(context, "FEATURE_TOGGLE PiggyBank=$enabled")
    }

    fun isHabitBadgesEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HABIT_BADGES, true)
    }

    fun setHabitBadgesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HABIT_BADGES, enabled).apply()
        EventLog.record(context, "FEATURE_TOGGLE HabitBadges=$enabled")
    }

    fun isExecutiveReportEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_EXECUTIVE_REPORT, true)
    }

    fun setExecutiveReportEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXECUTIVE_REPORT, enabled).apply()
        EventLog.record(context, "FEATURE_TOGGLE ExecutiveReport=$enabled")
    }
}
