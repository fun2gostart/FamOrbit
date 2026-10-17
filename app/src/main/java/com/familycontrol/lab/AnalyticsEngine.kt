package com.familycontrol.lab

import android.content.Context

data class CategoryUsage(
    val category: String,
    val icon: String,
    val totalMinutes: Long,
    val percentage: Int
)

data class DayUsageTrend(
    val dayLabel: String, // e.g. "Mon", "Tue"
    val minutes: Long,
    val isToday: Boolean = false
)

data class WeeklyAnalyticsReport(
    val totalScreenTimeMinutes: Long,
    val dailyAverageMinutes: Long,
    val topCategory: String,
    val categories: List<CategoryUsage>,
    val anomalyWarning: String?,
    val weeklyTrend: List<DayUsageTrend>
)

object AnalyticsEngine {

    fun categorizeApp(packageName: String, context: Context? = null): String {
        val lowerPkg = packageName.lowercase()

        // 1. Check parent custom category override if context is provided
        if (context != null) {
            val prefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
            val childId = ApiClient.serverChildId(context)
                ?: prefs.getString("server_child_id", null)
                ?: try { ChildProfileManager.getActiveChild(context).id } catch (_: Exception) { "" }
            val customCat = if (childId.isNotBlank()) {
                prefs.getString("${childId}_category_$packageName", null)
            } else {
                prefs.getString("category_$packageName", null)
            }
            if (!customCat.isNullOrBlank()) {
                return customCat
            }

            // 2. Query Android native ApplicationInfo.category if available (API 26+)
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    when (appInfo.category) {
                        android.content.pm.ApplicationInfo.CATEGORY_GAME -> return "Gaming"
                        android.content.pm.ApplicationInfo.CATEGORY_AUDIO,
                        android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> return "Entertainment"
                        android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Robust keyword & package heuristics
        return when {
            // Social Media & Messaging
            lowerPkg.contains("instagram") || lowerPkg.contains("facebook") || lowerPkg.contains("tiktok") ||
                    lowerPkg.contains("twitter") || lowerPkg.contains("snapchat") || lowerPkg.contains("whatsapp") ||
                    lowerPkg.contains("telegram") || lowerPkg.contains("reddit") || lowerPkg.contains("threads") ||
                    lowerPkg.contains("discord") || lowerPkg.contains("messenger") -> "Social"

            // Streaming, Video & Audio Entertainment (includes JioHotstar, Prime, etc.)
            lowerPkg.contains("hotstar") || lowerPkg.contains("startv") || lowerPkg.contains("netflix") ||
                    lowerPkg.contains("jio") || lowerPkg.contains("youtube") || lowerPkg.contains("hulu") ||
                    lowerPkg.contains("prime") || lowerPkg.contains("disney") || lowerPkg.contains("zee5") ||
                    lowerPkg.contains("sonyliv") || lowerPkg.contains("mxplayer") || lowerPkg.contains("spotify") ||
                    lowerPkg.contains("music") || lowerPkg.contains("twitch") || lowerPkg.contains("voot") ||
                    lowerPkg.contains("crunchyroll") || lowerPkg.contains("podcast") -> "Entertainment"

            // Games
            lowerPkg.contains("game") || lowerPkg.contains("roblox") || lowerPkg.contains("pubg") ||
                    lowerPkg.contains("minecraft") || lowerPkg.contains("clash") || lowerPkg.contains("candycrush") ||
                    lowerPkg.contains("subway") || lowerPkg.contains("freefire") || lowerPkg.contains("brawl") -> "Gaming"

            // Education & Learning
            lowerPkg.contains("duolingo") || lowerPkg.contains("khan") || lowerPkg.contains("coursera") ||
                    lowerPkg.contains("class") || lowerPkg.contains("byju") || lowerPkg.contains("unacademy") ||
                    lowerPkg.contains("quizlet") || lowerPkg.contains("edx") || lowerPkg.contains("udemy") -> "Education"

            // Default
            else -> "Productivity & Other"
        }
    }

    fun getWeeklyTrendReal(context: Context, todayMinutes: Long): List<DayUsageTrend> {
        val sdf = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val list = mutableListOf<DayUsageTrend>()
        val hasUsage = try { hasUsageAccess(context) } catch (_: Exception) { false }
        val manager = if (hasUsage) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        } else null

        val defaultHomePkg = try {
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName ?: ""
        } catch (_: Exception) { "" }

        fun isIgnoredPackage(pkg: String): Boolean {
            if (pkg == context.packageName) return true
            if (pkg == defaultHomePkg) return true
            val lower = pkg.lowercase()
            if (lower.contains("launcher") || lower.contains("quickstep") || lower.contains("trebuchet")) return true
            if (lower == "com.google.android.apps.nexuslauncher" || lower == "com.android.launcher3" || lower == "com.sec.android.app.launcher") return true
            if (lower == "com.android.systemui" || lower.contains("systemui")) return true
            if (lower.contains("keyguard") || lower.contains("wallpaper") || lower.contains("inputmethod")) return true
            return false
        }

        for (i in 6 downTo 0) {
            val calStart = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -i)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val calEnd = java.util.Calendar.getInstance().apply {
                timeInMillis = calStart.timeInMillis
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val label = sdf.format(calStart.time)
            val isToday = (i == 0)

            val mins = if (isToday) {
                todayMinutes
            } else if (manager != null) {
                try {
                    val stats = manager.queryAndAggregateUsageStats(calStart.timeInMillis, calEnd.timeInMillis)
                    val totalMs = stats.filter { (pkg, s) ->
                        s.totalTimeInForeground > 0L && !isIgnoredPackage(pkg) && !AppScanner.isSystemStub(pkg, "")
                    }.values.sumOf { it.totalTimeInForeground }
                    totalMs / 60_000L
                } catch (_: Exception) {
                    0L
                }
            } else {
                0L
            }

            list.add(
                DayUsageTrend(
                    dayLabel = label,
                    minutes = mins,
                    isToday = isToday
                )
            )
        }
        return list
    }

    fun getWeeklyTrend(todayMinutes: Long): List<DayUsageTrend> {
        val sdf = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val list = mutableListOf<DayUsageTrend>()
        for (i in 6 downTo 0) {
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -i)
            }
            val label = sdf.format(cal.time)
            val isToday = (i == 0)
            list.add(
                DayUsageTrend(
                    dayLabel = label,
                    minutes = if (isToday) todayMinutes else 0L,
                    isToday = isToday
                )
            )
        }
        return list
    }

    fun weeklyTrendToJson(trend: List<DayUsageTrend>): String {
        val arr = org.json.JSONArray()
        for (item in trend) {
            arr.put(
                org.json.JSONObject()
                    .put("day", item.dayLabel)
                    .put("minutes", item.minutes)
                    .put("is_today", item.isToday)
            )
        }
        return arr.toString()
    }

    fun parseWeeklyTrendJson(jsonStr: String, todayMinutes: Long): List<DayUsageTrend> {
        val list = mutableListOf<DayUsageTrend>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val day = obj.optString("day", "")
                val isToday = obj.optBoolean("is_today", false) || (i == arr.length() - 1)
                val mins = if (isToday) todayMinutes else obj.optLong("minutes", 0L)
                list.add(DayUsageTrend(dayLabel = day, minutes = mins, isToday = isToday))
            }
        } catch (_: Exception) {}
        return if (list.isNotEmpty()) list else getWeeklyTrend(todayMinutes)
    }

    fun generateReport(
        context: Context,
        todayUsage: List<AppUsage>,
        customWeeklyTrend: List<DayUsageTrend>? = null,
        overrideTotalMinutes: Long? = null
    ): WeeklyAnalyticsReport {
        val categoryMap = mutableMapOf<String, Long>()
        for (item in todayUsage) {
            val cat = categorizeApp(item.packageName, context)
            categoryMap[cat] = (categoryMap[cat] ?: 0L) + item.minutes
        }

        val totalMinutes = overrideTotalMinutes ?: todayUsage.sumOf { it.minutes }
        val denom = totalMinutes.coerceAtLeast(1L)
        val categories = listOf(
            CategoryUsage("Social", "📱", categoryMap["Social"] ?: 0L, (((categoryMap["Social"] ?: 0L) * 100) / denom).toInt()),
            CategoryUsage("Entertainment", "🎬", categoryMap["Entertainment"] ?: 0L, (((categoryMap["Entertainment"] ?: 0L) * 100) / denom).toInt()),
            CategoryUsage("Gaming", "🎮", categoryMap["Gaming"] ?: 0L, (((categoryMap["Gaming"] ?: 0L) * 100) / denom).toInt()),
            CategoryUsage("Education", "📚", categoryMap["Education"] ?: 0L, (((categoryMap["Education"] ?: 0L) * 100) / denom).toInt()),
            CategoryUsage("Productivity & Other", "🛠️", categoryMap["Productivity & Other"] ?: 0L, (((categoryMap["Productivity & Other"] ?: 0L) * 100) / denom).toInt())
        ).sortedByDescending { it.totalMinutes }

        val topCategory = if (totalMinutes > 0L) (categories.firstOrNull()?.category ?: "None") else "None"
        val trend = customWeeklyTrend ?: getWeeklyTrendReal(context, totalMinutes)
        val dailyAverage = if (trend.isNotEmpty()) trend.map { it.minutes }.average().toLong() else 0L

        val anomaly = if (totalMinutes > 240) {
            "⚠️ High screen time alert: Total daily screen time exceeded 4 hours today."
        } else if ((categoryMap["Gaming"] ?: 0L) > 90) {
            "🎮 Gaming spike alert: More than 90 minutes spent on gaming apps today."
        } else {
            null
        }

        return WeeklyAnalyticsReport(
            totalScreenTimeMinutes = totalMinutes,
            dailyAverageMinutes = dailyAverage,
            topCategory = topCategory,
            categories = categories,
            anomalyWarning = anomaly,
            weeklyTrend = trend
        )
    }

    fun emptyReport(): WeeklyAnalyticsReport {
        val emptyCats = listOf(
            CategoryUsage("Social", "📱", 0L, 0),
            CategoryUsage("Entertainment", "🎬", 0L, 0),
            CategoryUsage("Gaming", "🎮", 0L, 0),
            CategoryUsage("Education", "📚", 0L, 0),
            CategoryUsage("Productivity & Other", "🛠️", 0L, 0)
        )
        return WeeklyAnalyticsReport(
            totalScreenTimeMinutes = 0L,
            dailyAverageMinutes = 0L,
            topCategory = "None",
            categories = emptyCats,
            anomalyWarning = null,
            weeklyTrend = emptyList()
        )
    }
}
