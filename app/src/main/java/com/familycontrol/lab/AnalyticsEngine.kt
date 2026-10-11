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
            val childId = prefs.getString("server_child_id", null)
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

    fun getWeeklyTrend(todayMinutes: Long): List<DayUsageTrend> {
        val sdf = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val simulatedBase = listOf(110L, 145L, 95L, 180L, 160L, 125L)
        val list = mutableListOf<DayUsageTrend>()
        for (i in 6 downTo 0) {
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -i)
            }
            val label = sdf.format(cal.time)
            val isToday = (i == 0)
            val mins = if (isToday) todayMinutes else {
                val offsetIndex = (6 - i) % simulatedBase.size
                simulatedBase[offsetIndex]
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

    fun generateReport(context: Context, todayUsage: List<AppUsage>): WeeklyAnalyticsReport {
        val categoryMap = mutableMapOf<String, Long>()
        for (item in todayUsage) {
            val cat = categorizeApp(item.packageName, context)
            categoryMap[cat] = (categoryMap[cat] ?: 0L) + item.minutes
        }

        val totalMinutes = todayUsage.sumOf { it.minutes }.coerceAtLeast(1L)
        val categories = listOf(
            CategoryUsage("Social", "📱", categoryMap["Social"] ?: 0L, (((categoryMap["Social"] ?: 0L) * 100) / totalMinutes).toInt()),
            CategoryUsage("Entertainment", "🎬", categoryMap["Entertainment"] ?: 0L, (((categoryMap["Entertainment"] ?: 0L) * 100) / totalMinutes).toInt()),
            CategoryUsage("Gaming", "🎮", categoryMap["Gaming"] ?: 0L, (((categoryMap["Gaming"] ?: 0L) * 100) / totalMinutes).toInt()),
            CategoryUsage("Education", "📚", categoryMap["Education"] ?: 0L, (((categoryMap["Education"] ?: 0L) * 100) / totalMinutes).toInt()),
            CategoryUsage("Productivity & Other", "🛠️", categoryMap["Productivity & Other"] ?: 0L, (((categoryMap["Productivity & Other"] ?: 0L) * 100) / totalMinutes).toInt())
        ).sortedByDescending { it.totalMinutes }

        val topCategory = categories.firstOrNull()?.category ?: "None"
        val trend = getWeeklyTrend(totalMinutes)
        val dailyAverage = trend.map { it.minutes }.average().toLong()

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
}
