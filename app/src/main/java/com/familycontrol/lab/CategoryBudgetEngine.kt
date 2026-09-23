package com.familycontrol.lab

import android.content.Context
import org.json.JSONObject

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
    private const val KEY_LIMIT_EDUCATION = "limit_education"
    private const val KEY_LIMIT_PRODUCTIVITY = "limit_productivity"
    private const val KEY_LEARN_FIRST_ENABLED = "learn_first_enabled"
    private const val KEY_LEARN_FIRST_MINS = "learn_first_mins"

    private fun getChildPrefix(context: Context, childId: String?): String {
        val cid = childId ?: try { ChildProfileManager.getActiveChild(context).id } catch (_: Exception) { "" }
        return if (cid.isNotBlank()) "${cid}_" else ""
    }

    fun getSocialLimit(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LIMIT_SOCIAL")) {
            prefs.getInt("${prefix}$KEY_LIMIT_SOCIAL", 45)
        } else {
            prefs.getInt(KEY_LIMIT_SOCIAL, 45)
        }
    }

    fun setSocialLimit(context: Context, limit: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LIMIT_SOCIAL", limit)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LIMIT_SOCIAL, limit)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED Social=${limit}m child=$childId")
    }

    fun getGamingLimit(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LIMIT_GAMING")) {
            prefs.getInt("${prefix}$KEY_LIMIT_GAMING", 30)
        } else {
            prefs.getInt(KEY_LIMIT_GAMING, 30)
        }
    }

    fun setGamingLimit(context: Context, limit: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LIMIT_GAMING", limit)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LIMIT_GAMING, limit)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED Gaming=${limit}m child=$childId")
    }

    fun getEntertainmentLimit(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LIMIT_ENTERTAINMENT")) {
            prefs.getInt("${prefix}$KEY_LIMIT_ENTERTAINMENT", 60)
        } else {
            prefs.getInt(KEY_LIMIT_ENTERTAINMENT, 60)
        }
    }

    fun setEntertainmentLimit(context: Context, limit: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LIMIT_ENTERTAINMENT", limit)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LIMIT_ENTERTAINMENT, limit)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED Entertainment=${limit}m child=$childId")
    }

    fun getEducationLimit(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LIMIT_EDUCATION")) {
            prefs.getInt("${prefix}$KEY_LIMIT_EDUCATION", 60)
        } else {
            prefs.getInt(KEY_LIMIT_EDUCATION, 60)
        }
    }

    fun setEducationLimit(context: Context, limit: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LIMIT_EDUCATION", limit)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LIMIT_EDUCATION, limit)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED Education=${limit}m child=$childId")
    }

    fun getProductivityLimit(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LIMIT_PRODUCTIVITY")) {
            prefs.getInt("${prefix}$KEY_LIMIT_PRODUCTIVITY", 60)
        } else {
            prefs.getInt(KEY_LIMIT_PRODUCTIVITY, 60)
        }
    }

    fun setProductivityLimit(context: Context, limit: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LIMIT_PRODUCTIVITY", limit)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LIMIT_PRODUCTIVITY, limit)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED Productivity=${limit}m child=$childId")
    }

    fun isLearnFirstEnabled(context: Context, childId: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LEARN_FIRST_ENABLED")) {
            prefs.getBoolean("${prefix}$KEY_LEARN_FIRST_ENABLED", true)
        } else {
            prefs.getBoolean(KEY_LEARN_FIRST_ENABLED, true)
        }
    }

    fun setLearnFirstEnabled(context: Context, enabled: Boolean, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putBoolean("${prefix}$KEY_LEARN_FIRST_ENABLED", enabled)
        if (prefix.isNotEmpty()) editor.putBoolean(KEY_LEARN_FIRST_ENABLED, enabled)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED LearnFirstEnabled=$enabled child=$childId")
    }

    fun getLearnFirstRequiredMinutes(context: Context, childId: String? = null): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        return if (prefix.isNotEmpty() && prefs.contains("${prefix}$KEY_LEARN_FIRST_MINS")) {
            prefs.getInt("${prefix}$KEY_LEARN_FIRST_MINS", 30)
        } else {
            prefs.getInt(KEY_LEARN_FIRST_MINS, 30)
        }
    }

    fun setLearnFirstRequiredMinutes(context: Context, minutes: Int, childId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = getChildPrefix(context, childId)
        val editor = prefs.edit().putInt("${prefix}$KEY_LEARN_FIRST_MINS", minutes)
        if (prefix.isNotEmpty()) editor.putInt(KEY_LEARN_FIRST_MINS, minutes)
        editor.apply()
        EventLog.record(context, "CATEGORY_BUDGET_CHANGED LearnFirstReqMins=${minutes}m child=$childId")
    }

    fun toJson(context: Context, childId: String? = null): JSONObject {
        return JSONObject()
            .put("social_limit", getSocialLimit(context, childId))
            .put("gaming_limit", getGamingLimit(context, childId))
            .put("entertainment_limit", getEntertainmentLimit(context, childId))
            .put("education_limit", getEducationLimit(context, childId))
            .put("productivity_limit", getProductivityLimit(context, childId))
            .put("learn_first_enabled", isLearnFirstEnabled(context, childId))
            .put("learn_first_mins", getLearnFirstRequiredMinutes(context, childId))
    }

    fun applyJson(context: Context, json: JSONObject, childId: String? = null) {
        if (json.has("social_limit")) setSocialLimit(context, json.optInt("social_limit", 45), childId)
        if (json.has("gaming_limit")) setGamingLimit(context, json.optInt("gaming_limit", 30), childId)
        if (json.has("entertainment_limit")) setEntertainmentLimit(context, json.optInt("entertainment_limit", 60), childId)
        if (json.has("education_limit")) setEducationLimit(context, json.optInt("education_limit", 60), childId)
        if (json.has("productivity_limit")) setProductivityLimit(context, json.optInt("productivity_limit", 60), childId)
        if (json.has("learn_first_enabled")) setLearnFirstEnabled(context, json.optBoolean("learn_first_enabled", true), childId)
        if (json.has("learn_first_mins")) setLearnFirstRequiredMinutes(context, json.optInt("learn_first_mins", 30), childId)
    }

    fun getCategoryForPackage(packageName: String): String {
        return AnalyticsEngine.categorizeApp(packageName)
    }

    fun getCategorySummaries(context: Context, usage: List<AppUsage>, childId: String? = null): List<CategoryUsageSummary> {
        var socialUsed = 0L
        var gamingUsed = 0L
        var entUsed = 0L
        var eduUsed = 0L
        var prodUsed = 0L

        for (u in usage) {
            when (getCategoryForPackage(u.packageName)) {
                "Social" -> socialUsed += u.minutes
                "Gaming" -> gamingUsed += u.minutes
                "Entertainment" -> entUsed += u.minutes
                "Education" -> eduUsed += u.minutes
                "Productivity & Other" -> prodUsed += u.minutes
                else -> prodUsed += u.minutes
            }
        }

        return listOf(
            CategoryUsageSummary("Social", "💬", socialUsed, getSocialLimit(context, childId)),
            CategoryUsageSummary("Gaming", "🎮", gamingUsed, getGamingLimit(context, childId)),
            CategoryUsageSummary("Entertainment", "🍿", entUsed, getEntertainmentLimit(context, childId)),
            CategoryUsageSummary("Education", "📚", eduUsed, getEducationLimit(context, childId)),
            CategoryUsageSummary("Productivity & Other", "💼", prodUsed, getProductivityLimit(context, childId))
        )
    }

    fun isGamingBlockedByLearnFirst(context: Context, usage: List<AppUsage>, childId: String? = null): Boolean {
        if (!isLearnFirstEnabled(context, childId)) return false
        val req = getLearnFirstRequiredMinutes(context, childId)
        val prodUsed = usage
            .filter { getCategoryForPackage(it.packageName) == "Education" || getCategoryForPackage(it.packageName) == "Productivity & Other" }
            .sumOf { it.minutes }
        return prodUsed < req
    }
}
