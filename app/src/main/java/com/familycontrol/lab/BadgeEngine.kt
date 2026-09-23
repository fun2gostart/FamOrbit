package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BadgeItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val isUnlocked: Boolean
)

object BadgeEngine {
    private const val PREFS = "badge_engine_v2"

    val DEFAULT_BADGE_DEFINITIONS = listOf(
        BadgeItem("agreement", "Digital Agreement Signed", "Parent & Child signed the family digital pledge", "✍️", false),
        BadgeItem("focus_streak", "7-Day Focus Streak", "Maintained Focus Mode daily for 7 consecutive days", "🏆", false),
        BadgeItem("homework_hero", "Homework Hero", "Completed all daily homework & study habits", "✏️", false),
        BadgeItem("early_sleeper", "Early Sleeper", "Respected Bedtime Lock without late-night requests", "🌙", false),
        BadgeItem("active_balanced", "Balanced Life", "Balanced screen time with outdoor & physical activities", "⚽", false)
    )

    fun getBadgesForChild(context: Context, childId: String? = null): List<BadgeItem> {
        val effectiveChildId = childId ?: ChildProfileManager.getActiveChild(context).id
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return DEFAULT_BADGE_DEFINITIONS.map { def ->
            val unlocked = prefs.getBoolean("${effectiveChildId}_${def.id}", false)
            def.copy(isUnlocked = unlocked)
        }
    }

    fun getAllBadges(context: Context): List<BadgeItem> {
        return getBadgesForChild(context, null)
    }

    fun setBadgeUnlocked(context: Context, childId: String, badgeId: String, unlocked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${childId}_${badgeId}", unlocked).apply()
        EventLog.record(context, "BADGE_UPDATED child=$childId badge=$badgeId unlocked=$unlocked")
    }

    fun isAgreementSigned(context: Context, childId: String? = null): Boolean {
        val effectiveChildId = childId ?: ChildProfileManager.getActiveChild(context).id
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("${effectiveChildId}_agreement", false)
    }

    fun signAgreement(context: Context, childId: String? = null) {
        val effectiveChildId = childId ?: ChildProfileManager.getActiveChild(context).id
        setBadgeUnlocked(context, effectiveChildId, "agreement", true)
        EventLog.record(context, "AGREEMENT_SIGNED Digital pledge signed by Parent & Child for $effectiveChildId")
    }

    fun getUnlockedCount(context: Context, childId: String? = null): Int {
        return getBadgesForChild(context, childId).count { it.isUnlocked }
    }
}
