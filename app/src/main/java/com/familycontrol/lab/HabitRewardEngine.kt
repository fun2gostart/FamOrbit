package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HabitItem(
    val id: String,
    val title: String,
    val icon: String,
    val rewardMinutes: Int = 15
)

object HabitRewardEngine {
    private const val PREFS = "habit_rewards"

    val defaultHabits = listOf(
        HabitItem("reading", "Read a Book for 30 Mins", "📚", 15),
        HabitItem("homework", "Complete Homework", "✏️", 15),
        HabitItem("chores", "Tidy Room / Family Chores", "🧹", 15),
        HabitItem("exercise", "30 Mins Outdoor Exercise", "⚽", 15)
    )

    fun claimHabit(context: Context, habitId: String, packageName: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val habit = defaultHabits.firstOrNull { it.id == habitId } ?: return
        val raw = prefs.getString("claims", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val claim = JSONObject()
                .put("id", habit.id)
                .put("title", habit.title)
                .put("reward", habit.rewardMinutes)
                .put("package_name", packageName)
                .put("status", "PENDING")
                .put("claimed_at", System.currentTimeMillis())
            arr.put(claim)
            prefs.edit().putString("claims", arr.toString()).apply()
            EventLog.record(context, "HABIT_CLAIMED ${habit.title}")
        } catch (_: Exception) {}
    }

    fun approveHabitClaim(context: Context, claimIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("claims", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            if (claimIndex in 0 until arr.length()) {
                val obj = arr.getJSONObject(claimIndex)
                obj.put("status", "APPROVED")
                val pkg = obj.optString("package_name", "com.instagram.android")
                val reward = obj.optInt("reward", 15)

                // Add reward minutes directly to ExtraTimeLedger!
                val remoteAllowances = JSONArray().put(
                    JSONObject()
                        .put("request_id", "habit_${System.currentTimeMillis()}")
                        .put("package_name", pkg)
                        .put("approved_minutes", reward)
                        .put("consumed_minutes", 0)
                        .put("expires_at", System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                )
                ExtraTimeLedger.updateFromRemote(context, remoteAllowances)
                prefs.edit().putString("claims", arr.toString()).apply()
                EventLog.record(context, "HABIT_APPROVED +${reward}m for ${AppNameResolver.getAppName(context, pkg)}")
            }
        } catch (_: Exception) {}
    }
}
