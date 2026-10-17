package com.familycontrol.lab

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class DailyBalance(
    val baseLimitMinutes: Int,
    val bonusMinutesToday: Int,
    val effectiveLimitMinutes: Int,
    val usedMinutes: Long,
    val remainingMinutes: Long,
    val isLimitReached: Boolean
)

object DailyBalanceResolver {

    /**
     * Parses ISO-8601 string or numeric timestamp to epoch milliseconds.
     * Resiliently handles:
     * - Epoch millisecond numbers and strings
     * - ISO strings with timezone offsets (e.g. 2026-10-13T04:22:50.373744+00:00)
     * - ISO strings with UTC 'Z' (e.g. 2026-10-13T04:22:50Z)
     * - Naive ISO strings without timezone offsets
     */
    fun parseTimestamp(raw: Any?): Long {
        if (raw == null) return 0L
        if (raw is Number) return raw.toLong()
        val str = raw.toString().trim()
        if (str.isEmpty() || str == "null") return 0L
        str.toLongOrNull()?.let { return it }

        return try {
            OffsetDateTime.parse(str).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(str).toEpochMilli()
            } catch (_: Exception) {
                try {
                    val cleanIso = if (str.length >= 19) str.substring(0, 19) else str
                    val ldt = LocalDateTime.parse(cleanIso)
                    ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        sdf.parse(str.take(19))?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                }
            }
        }
    }

    /**
     * Checks if a timestamp falls within today's calendar date in local timezone.
     */
    fun isToday(timestampMs: Long): Boolean {
        if (timestampMs <= 0L) return true // Fallback: assume today if unspecified
        val calNow = Calendar.getInstance()
        val calTarget = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return calNow.get(Calendar.YEAR) == calTarget.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calTarget.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Persistently grants approved bonus time for a child.
     * Clears any stale revocation and updates local persistence so that bonus time
     * survives app kills, restarts, and offline periods.
     */
    fun grantApprovedBonus(context: Context, childId: String, requestId: String, minutes: Int) {
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val targetChild = childId.ifBlank {
            runCatching { ChildProfileManager.getActiveChild(context).id }.getOrDefault("")
        }

        // 1. Clear any stale revocation timestamps so this new approval is active immediately
        clearRevocation(context, targetChild)

        // 2. Clear requestId from dismissed sets
        val currentDismissed = pPrefs.getStringSet("parent_dismissed_request_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentDismissed.remove(requestId)) {
            pPrefs.edit().putStringSet("parent_dismissed_request_ids", currentDismissed).apply()
        }

        // 3. Increment persistent bonus minutes for this child today
        val currentBonus = pPrefs.getInt("${targetChild}_bonus_minutes_today", 0)
        val updatedBonus = currentBonus + minutes
        pPrefs.edit()
            .putInt("${targetChild}_bonus_minutes_today", updatedBonus)
            .putLong("${targetChild}_bonus_updated_at", System.currentTimeMillis())
            .apply()

        // 4. Update ExtraTimeLedger and ExtraTimeRequestEngine
        ExtraTimeLedger.addExtraTime(context, "", minutes, requestId)
        ExtraTimeRequestEngine.approveRequest(context, requestId, fallbackPackageName = "", fallbackMinutes = minutes)
        ExtraTimeUpdateTrigger.notifyChange()
    }

    /**
     * Revokes all active extra bonus time for a child profile.
     */
    fun revokeAllBonus(context: Context, childId: String) {
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val targetChild = childId.ifBlank {
            runCatching { ChildProfileManager.getActiveChild(context).id }.getOrDefault("")
        }
        val now = System.currentTimeMillis()
        val editor = pPrefs.edit()
            .putInt("${targetChild}_bonus_minutes_today", 0)
            .putLong("${targetChild}_extra_time_revoked_at", now)
            .putLong("extra_time_revoked_at", now)
        val srvChildId = ApiClient.serverChildId(context)
        if (!srvChildId.isNullOrBlank()) {
            editor.putLong("${srvChildId}_extra_time_revoked_at", now)
            editor.putInt("${srvChildId}_bonus_minutes_today", 0)
        }
        editor.apply()

        ExtraTimeLedger.clearAllExtraTime(context)
        ExtraTimeRequestEngine.clearAllRequests(context)
    }

    /**
     * Clears revocation timestamps when newly approved time is granted.
     */
    fun clearRevocation(context: Context, childId: String) {
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val targetChild = childId.ifBlank {
            runCatching { ChildProfileManager.getActiveChild(context).id }.getOrDefault("")
        }
        val srvChildId = ApiClient.serverChildId(context)
        val editor = pPrefs.edit()
            .remove("${targetChild}_extra_time_revoked_at")
            .remove("extra_time_revoked_at")
        if (!srvChildId.isNullOrBlank()) {
            editor.remove("${srvChildId}_extra_time_revoked_at")
        }
        editor.apply()
    }

    /**
     * Resolves the complete daily balance for a given child.
     * Works identically across Parent Dashboard, Parent Control Center,
     * Child View on Parent Phone, and the physical Child Device.
     */
    fun resolve(
        context: Context,
        targetChildId: String = "",
        jsonRequests: List<JSONObject> = emptyList()
    ): DailyBalance {
        val role = ApiClient.getDeviceRole(context)
        val pPrefs = context.getSharedPreferences("parent_control", Context.MODE_PRIVATE)

        if (role == ApiClient.ROLE_CHILD) {
            // Child Device logic
            val childId = targetChildId.ifBlank {
                ApiClient.serverChildId(context)
                    ?: runCatching { ChildProfileManager.getActiveChild(context).id }.getOrDefault("")
            }
            val baseLimit = if (childId.isNotEmpty() && pPrefs.contains("${childId}_daily_screen_limit")) {
                pPrefs.getInt("${childId}_daily_screen_limit", 180)
            } else {
                pPrefs.getInt("daily_screen_limit", 180)
            }

            // General extra time for daily allowance is stored with empty package name in ExtraTimeLedger
            val bonusToday = ExtraTimeLedger.getRemainingExtraMinutes(context, "")
            val effectiveLimit = (baseLimit + bonusToday).coerceAtLeast(0)

            val usedToday = AccessibilityGuardEngine.getEffectiveTodayUsage(context).sumOf { it.minutes }
            val remaining = (effectiveLimit - usedToday).coerceAtLeast(0L)
            val isLimitReached = effectiveLimit > 0 && usedToday >= effectiveLimit

            return DailyBalance(
                baseLimitMinutes = baseLimit,
                bonusMinutesToday = bonusToday,
                effectiveLimitMinutes = effectiveLimit,
                usedMinutes = usedToday,
                remainingMinutes = remaining,
                isLimitReached = isLimitReached
            )
        } else {
            // Parent Device logic
            val effectiveChildId = targetChildId.ifBlank {
                runCatching { ChildProfileManager.getActiveChild(context).id }.getOrDefault("")
            }
            val srvChildId = ApiClient.serverChildId(context) ?: ""

            // 1. Base Daily Limit
            val childProfile = ChildProfileManager.getChildren(context).find { it.id == effectiveChildId }
            val baseLimit = childProfile?.dailyScreenLimitMinutes
                ?: pPrefs.getInt("${effectiveChildId}_daily_screen_limit", 180)

            // If this child profile has NO paired devices, usage is 0, bonus is 0, and limit is NOT reached
            val activeDevices = if (effectiveChildId.isNotBlank()) ChildDeviceManager.getDevicesForChild(context, effectiveChildId) else emptyList()
            if (activeDevices.isEmpty()) {
                return DailyBalance(
                    baseLimitMinutes = baseLimit,
                    bonusMinutesToday = 0,
                    effectiveLimitMinutes = baseLimit,
                    usedMinutes = 0L,
                    remainingMinutes = baseLimit.toLong(),
                    isLimitReached = false
                )
            }

            // 2. Revocation timestamp
            val revokedAt = maxOf(
                pPrefs.getLong("${effectiveChildId}_extra_time_revoked_at", 0L),
                pPrefs.getLong("extra_time_revoked_at", 0L),
                if (srvChildId.isNotBlank()) pPrefs.getLong("${srvChildId}_extra_time_revoked_at", 0L) else 0L
            )

            // 3. Persistent cached bonus minutes today
            val lastBonusUpdate = pPrefs.getLong("${effectiveChildId}_bonus_updated_at", 0L)
            val cachedBonusToday = if (isToday(lastBonusUpdate) && (revokedAt == 0L || lastBonusUpdate > revokedAt)) {
                pPrefs.getInt("${effectiveChildId}_bonus_minutes_today", 0)
            } else {
                0
            }

            // 4. Calculate bonus from ExtraTimeRequestEngine and jsonRequests
            val engineRequests = ExtraTimeRequestEngine.getAllRequests(context)
            val seenRequestIds = mutableSetOf<String>()
            var calculatedBonus = 0

            for (req in engineRequests) {
                val isApproved = req.status.equals("APPROVED", ignoreCase = true)
                val isGeneral = req.packageName.isBlank() || req.packageName == "null" || req.packageName == "None"
                val isForChild = effectiveChildId.isBlank() || req.childId.isBlank() ||
                        req.childId == effectiveChildId || (srvChildId.isNotBlank() && req.childId == srvChildId)
                val afterRevocation = (revokedAt == 0L || req.timestamp > revokedAt)
                val isCreatedToday = isToday(req.timestamp)

                if (isApproved && isGeneral && isForChild && afterRevocation && isCreatedToday) {
                    if (seenRequestIds.add(req.id)) {
                        calculatedBonus += req.requestedMinutes
                    }
                }
            }

            // Also check any JSON requests passed from direct API calls (e.g. in ChildHomeScreen / ParentControlScreen)
            for (obj in jsonRequests) {
                val reqId = obj.optString("request_id")
                if (reqId.isBlank() || seenRequestIds.contains(reqId)) continue

                val isApproved = obj.optString("status").equals("APPROVED", ignoreCase = true)
                val pkg = obj.optString("package_name")
                val isGeneral = pkg.isBlank() || pkg == "null" || pkg == "None"
                val rawCreated = obj.opt("created_at_epoch") ?: obj.opt("created_at")
                val createdEpoch = parseTimestamp(rawCreated)
                val afterRevocation = (revokedAt == 0L || createdEpoch == 0L || createdEpoch > revokedAt)
                val isCreatedToday = isToday(createdEpoch)

                if (isApproved && isGeneral && afterRevocation && isCreatedToday) {
                    if (seenRequestIds.add(reqId)) {
                        calculatedBonus += obj.optInt("requested_minutes", 0)
                    }
                }
            }

            // The effective bonus is the maximum of the persistently cached bonus and the calculated bonus
            val finalBonus = maxOf(cachedBonusToday, calculatedBonus)

            // Keep SharedPreferences updated with the resolved bonus
            if (finalBonus > 0 && finalBonus != cachedBonusToday) {
                pPrefs.edit()
                    .putInt("${effectiveChildId}_bonus_minutes_today", finalBonus)
                    .putLong("${effectiveChildId}_bonus_updated_at", System.currentTimeMillis())
                    .apply()
            }

            val effectiveLimit = (baseLimit + finalBonus).coerceAtLeast(0)

            // 5. Used screen time
            val syncedUsed = pPrefs.getLong("${effectiveChildId}_today_screen_time", -1L)
            val usedToday = if (syncedUsed >= 0L) syncedUsed else 0L

            val remaining = (effectiveLimit - usedToday).coerceAtLeast(0L)
            val isLimitReached = effectiveLimit > 0 && usedToday >= effectiveLimit

            return DailyBalance(
                baseLimitMinutes = baseLimit,
                bonusMinutesToday = finalBonus,
                effectiveLimitMinutes = effectiveLimit,
                usedMinutes = usedToday,
                remainingMinutes = remaining,
                isLimitReached = isLimitReached
            )
        }
    }
}
