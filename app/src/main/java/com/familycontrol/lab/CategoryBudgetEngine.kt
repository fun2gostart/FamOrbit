package com.familycontrol.lab

import android.content.Context

data class CategoryUsageSummary(
    val category: String,
    val icon: String,
    val usedMinutes: Long,
    val limitMinutes: Int
)

object CategoryBudgetEngine {
    private const val PREFS = "category_budgets_v1"
    private const val KEY_LIMIT_SOCIAL = "limit_social"
    private const val KEY_LIMIT_GAMING = "limit_gaming"
    private const val KEY_LIMIT_ENTERTAINMENT = "limit_entertainment"
    private const val KEY_LEARN_FIRST_ENABLED = "learn_first_enabled"
    private const val KEY_LEARN_FIRST_MINS = "learn_first_mins"

    fun getSocialLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT_SOCIAL, 45)
    }

    fun setSocialLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIMIT_SOCIAL, limit).apply()
    }

    fun getGamingLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT_GAMING, 30)
    }

    fun setGamingLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIMIT_GAMING, limit).apply()
    }

    fun getEntertainmentLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT_ENTERTAINMENT, 60)
    }

    fun setEntertainmentLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIMIT_ENTERTAINMENT, limit).apply()
    }

    fun isLearnFirstEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LEARN_FIRST_ENABLED, true)
    }

    fun setLearnFirstEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LEARN_FIRST_ENABLED, enabled).apply()
    }

    fun getLearnFirstRequiredMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEARN_FIRST_MINS, 30)
    }

    fun setLearnFirstRequiredMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LEARN_FIRST_MINS, minutes).apply()
    }

    fun getCategoryForPackage(packageName: String): String {
        val lower = packageName.lowercase()
        return when {
            lower.contains("instagram") || lower.contains("facebook") || lower.contains("twitter") ||
            lower.contains("snapchat") || lower.contains("tiktok") || lower.contains("whatsapp") ||
            lower.contains("telegram") -> "Social"

            lower.contains("game") || lower.contains("pubg") || lower.contains("roblox") ||
            lower.contains("miners") || lower.contains("subway") || lower.contains("candy") -> "Gaming"

            lower.contains("netflix") || lower.contains("jioplay") || lower.contains("youtube") ||
            lower.contains("prime") || lower.contains("hulu") || lower.contains("disney") -> "Entertainment"

            else -> "Education & Productivity"
        }
    }

    fun getCategorySummaries(context: Context, usage: List<AppUsage>): List<CategoryUsageSummary> {
        var socialUsed = 0L
        var gamingUsed = 0L
        var entUsed = 0L

        for (u in usage) {
            when (getCategoryForPackage(u.packageName)) {
                "Social" -> socialUsed += u.minutes
                "Gaming" -> gamingUsed += u.minutes
                "Entertainment" -> entUsed += u.minutes
            }
        }

        return listOf(
            CategoryUsageSummary("Social", "💬", socialUsed, getSocialLimit(context)),
            CategoryUsageSummary("Gaming", "🎮", gamingUsed, getGamingLimit(context)),
            CategoryUsageSummary("Entertainment", "🍿", entUsed, getEntertainmentLimit(context))
        )
    }

    fun isGamingBlockedByLearnFirst(context: Context, usage: List<AppUsage>): Boolean {
        if (!isLearnFirstEnabled(context)) return false
        val req = getLearnFirstRequiredMinutes(context)
        val prodUsed = usage
            .filter { getCategoryForPackage(it.packageName) == "Education & Productivity" }
            .sumOf { it.minutes }
        return prodUsed < req
    }
}
