package com.familycontrol.lab

import android.content.Context

data class BadgeItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val isUnlocked: Boolean
)

object BadgeEngine {
    private const val PREFS = "badge_engine_v1"
    private const val KEY_SIGNED = "agreement_signed"

    fun isAgreementSigned(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SIGNED, true)
    }

    fun signAgreement(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SIGNED, true).apply()
        EventLog.record(context, "AGREEMENT_SIGNED Digital pledge signed by Parent & Child")
    }

    fun getAllBadges(context: Context): List<BadgeItem> {
        val signed = isAgreementSigned(context)
        return listOf(
            BadgeItem("agreement", "Digital Agreement Signed", "Parent & Child signed the family digital pledge", "✍️", signed),
            BadgeItem("focus_streak", "7-Day Focus Streak", "Maintained Focus Mode daily for 7 consecutive days", "🏆", true),
            BadgeItem("homework_hero", "Homework Hero", "Completed all daily homework habits this week", "✏️", true),
            BadgeItem("early_sleeper", "Early Sleeper", "Respected Bedtime Lock without deactivation requests", "🌙", true)
        )
    }
}
