package com.familycontrol.lab

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

data class FamilyRoutine(
    val id: String,
    val name: String,
    val start: String,
    val end: String,
    val weekdaysOnly: Boolean,
    val enabled: Boolean
)

object ScheduleEngine {
    private const val PREFS = "routines"

    val defaults = listOf(
        FamilyRoutine("school", "School", "08:00", "14:00", true, false),
        FamilyRoutine("homework", "Homework", "16:00", "18:00", true, false),
        FamilyRoutine("family", "Family Time", "19:00", "20:00", false, false),
        FamilyRoutine("bedtime", "Bedtime", "21:30", "07:00", false, false)
    )

    fun load(context: Context): List<FamilyRoutine> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedIds = prefs.getStringSet("all_ids", null)
        if (savedIds == null) {
            return defaults.map { d ->
                d.copy(
                    name = prefs.getString("${d.id}_name", d.name) ?: d.name,
                    start = prefs.getString("${d.id}_start", d.start) ?: d.start,
                    end = prefs.getString("${d.id}_end", d.end) ?: d.end,
                    weekdaysOnly = prefs.getBoolean("${d.id}_weekdays", d.weekdaysOnly),
                    enabled = prefs.getBoolean("${d.id}_enabled", d.enabled)
                )
            }
        }
        return savedIds.mapNotNull { id ->
            val defaultR = defaults.firstOrNull { it.id == id }
            val name = prefs.getString("${id}_name", defaultR?.name) ?: defaultR?.name ?: return@mapNotNull null
            val start = prefs.getString("${id}_start", defaultR?.start ?: "08:00") ?: "08:00"
            val end = prefs.getString("${id}_end", defaultR?.end ?: "17:00") ?: "17:00"
            val weekdays = prefs.getBoolean("${id}_weekdays", defaultR?.weekdaysOnly ?: false)
            val enabled = prefs.getBoolean("${id}_enabled", defaultR?.enabled ?: false)
            FamilyRoutine(id, name, start, end, weekdays, enabled)
        }
    }

    fun save(context: Context, routine: FamilyRoutine) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == routine.id }
        if (index >= 0) {
            current[index] = routine
        } else {
            current.add(routine)
        }
        saveAll(context, current)
    }

    fun saveAll(context: Context, routines: List<FamilyRoutine>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val ids = routines.map { it.id }.toSet()
        editor.putStringSet("all_ids", ids)
        routines.forEach { r ->
            editor.putString("${r.id}_name", r.name)
            editor.putString("${r.id}_start", r.start)
            editor.putString("${r.id}_end", r.end)
            editor.putBoolean("${r.id}_weekdays", r.weekdaysOnly)
            editor.putBoolean("${r.id}_enabled", r.enabled)
        }
        editor.apply()
    }

    fun delete(context: Context, routineId: String) {
        val updated = load(context).filterNot { it.id == routineId }
        saveAll(context, updated)
    }

    fun activeRoutine(context: Context, now: LocalDateTime = LocalDateTime.now()): FamilyRoutine? {
        val day = now.dayOfWeek
        val time = now.toLocalTime()

        return load(context).firstOrNull { routine ->
            if (!routine.enabled) return@firstOrNull false
            if (routine.weekdaysOnly && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)) return@firstOrNull false
            val start = parseTime(routine.start)
            val end = parseTime(routine.end)

            if (start <= end) {
                !time.isBefore(start) && time.isBefore(end)
            } else {
                // Overnight routine, e.g. 21:30 → 07:00.
                !time.isBefore(start) || time.isBefore(end)
            }
        }
    }

    fun isAppRestrictedByRoutine(context: Context, packageName: String): Boolean {
        val routine = activeRoutine(context) ?: return false
        val entertainmentApps = setOf(
            "com.instagram.android",
            "com.jio.jioPlay.tv",
            "com.netflix.mediaclient"
        )
        return packageName in entertainmentApps
    }

    private fun parseTime(value: String): LocalTime =
        try {
            LocalTime.parse(value)
        } catch (_: Exception) {
            LocalTime.MIDNIGHT
        }
}
