package com.familycontrol.lab

import android.content.Context

data class ParentInsight(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val recommendation: String
)

object AICoachEngine {

    fun generateInsights(context: Context): List<ParentInsight> {
        val usage = getTodayUsage(context)
        val totalMinutes = usage.sumOf { it.minutes }
        val activeRoutine = ScheduleEngine.activeRoutine(context)

        val list = mutableListOf<ParentInsight>()

        list.add(
            ParentInsight(
                id = "routine_advisory",
                type = "ROUTINE",
                title = "Smart Family Routine Advisory",
                description = if (activeRoutine != null) "Active Routine: ${activeRoutine.name} (${activeRoutine.start} – ${activeRoutine.end})" else "No active routine window right now.",
                recommendation = "Consistent bedtime routines improve digital wellbeing and sleep hygiene."
            )
        )

        if (totalMinutes > 120) {
            list.add(
                ParentInsight(
                    id = "high_usage_alert",
                    type = "ALERT",
                    title = "Daily Screen-time Summary",
                    description = "Total screen time today has reached ${totalMinutes} minutes.",
                    recommendation = "Consider reviewing daily app limits or encouraging a 30-minute outdoor break."
                )
            )
        }

        val topApp = usage.maxByOrNull { it.minutes }
        if (topApp != null && topApp.minutes > 30) {
            val appName = when (topApp.packageName) {
                "com.instagram.android" -> "Instagram"
                "com.jio.jioPlay.tv" -> "JioPlayTV"
                "com.netflix.mediaclient" -> "Netflix"
                else -> topApp.packageName.split('.').last().capitalize()
            }
            list.add(
                ParentInsight(
                    id = "top_app_insight",
                    type = "USAGE_TREND",
                    title = "$appName Engagement",
                    description = "Highest used app today is $appName with ${topApp.minutes} mins.",
                    recommendation = "Review per-app limits in Parent Control Center if evening usage increases."
                )
            )
        }

        return list
    }
}
