package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ExtraTimeRequest(
    val id: String,
    val packageName: String,
    val appName: String,
    val requestedMinutes: Int,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING",
    val childId: String = "",
    val childName: String = "",
    val childAvatar: String = "👶"
)

object ExtraTimeRequestEngine {
    private const val PREFS = "extra_time_requests_v1"
    private const val KEY_REQUESTS = "extra_time_requests_v1"
    private const val KEY_DISMISSED_IDS = "dismissed_request_ids_v1"

    fun getDismissedIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DISMISSED_IDS, emptySet()) ?: emptySet()
    }

    fun addDismissedId(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DISMISSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(id)
        prefs.edit().putStringSet(KEY_DISMISSED_IDS, current).apply()
    }

    fun addDismissedIds(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DISMISSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.addAll(ids)
        prefs.edit().putStringSet(KEY_DISMISSED_IDS, current).apply()
    }

    fun getAllRequests(context: Context): List<ExtraTimeRequest> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REQUESTS, null) ?: return emptyList()
        val dismissed = getDismissedIds(context)
        val result = mutableListOf<ExtraTimeRequest>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                if (!dismissed.contains(id)) {
                    result.add(
                        ExtraTimeRequest(
                            id = id,
                            packageName = obj.optString("packageName", ""),
                            appName = obj.optString("appName", ""),
                            requestedMinutes = obj.optInt("requestedMinutes", 15),
                            reason = obj.optString("reason", "Need extra time"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            status = obj.optString("status", "PENDING"),
                            childId = obj.optString("childId", ""),
                            childName = obj.optString("childName", ""),
                            childAvatar = obj.optString("childAvatar", "👶")
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return result.sortedByDescending { it.timestamp }
    }

    fun getPendingRequests(context: Context): List<ExtraTimeRequest> {
        return getAllRequests(context).filter { it.status == "PENDING" }
    }

    fun submitRequest(
        context: Context,
        packageName: String,
        appName: String,
        requestedMinutes: Int,
        reason: String
    ): ExtraTimeRequest {
        val all = getAllRequests(context).toMutableList()
        val newReq = ExtraTimeRequest(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            appName = appName,
            requestedMinutes = requestedMinutes,
            reason = if (reason.isBlank()) "Homework & Study" else reason,
            timestamp = System.currentTimeMillis(),
            status = "PENDING"
        )
        all.add(0, newReq)
        saveAll(context, all)
        return newReq
    }

    fun approveRequest(context: Context, requestId: String) {
        val all = getAllRequests(context).toMutableList()
        val index = all.indexOfFirst { it.id == requestId }
        if (index != -1) {
            val req = all[index]
            all[index] = req.copy(status = "APPROVED")
            saveAll(context, all)

            // Update local ExtraTimeLedger to immediately grant extra minutes
            val allowancesJson = JSONArray()
            val itemObj = JSONObject().apply {
                put("request_id", req.id)
                put("package_name", req.packageName)
                put("approved_minutes", req.requestedMinutes)
                put("consumed_minutes", 0)
                put("expires_at", System.currentTimeMillis() + 86400000L) // 24h validity
            }
            allowancesJson.put(itemObj)
            ExtraTimeLedger.updateFromRemote(context, allowancesJson)
        }
    }

    fun denyRequest(context: Context, requestId: String) {
        val all = getAllRequests(context).toMutableList()
        val index = all.indexOfFirst { it.id == requestId }
        if (index != -1) {
            val req = all[index]
            all[index] = req.copy(status = "DENIED")
            saveAll(context, all)
        }
    }

    fun deleteRequest(context: Context, requestId: String) {
        addDismissedId(context, requestId)
        val all = getAllRequests(context).filter { it.id != requestId }
        saveAll(context, all)
    }

    fun clearHandledRequests(context: Context) {
        val all = getAllRequests(context)
        val handled = all.filter { it.status != "PENDING" }
        addDismissedIds(context, handled.map { it.id })
        val remaining = all.filter { it.status == "PENDING" }
        saveAll(context, remaining)
    }

    fun syncCloudRequest(
        context: Context,
        id: String,
        packageName: String,
        appName: String,
        requestedMinutes: Int,
        reason: String,
        status: String = "PENDING",
        childId: String = "",
        childName: String = "",
        childAvatar: String = "👶"
    ) {
        // If this request was already cleared/dismissed by the user, ignore it completely
        if (getDismissedIds(context).contains(id)) {
            return
        }

        val all = getAllRequests(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index != -1) {
            all[index] = all[index].copy(
                status = status,
                childId = childId,
                childName = childName,
                childAvatar = childAvatar
            )
        } else {
            all.add(
                0,
                ExtraTimeRequest(
                    id = id,
                    packageName = packageName,
                    appName = appName,
                    requestedMinutes = requestedMinutes,
                    reason = reason,
                    timestamp = System.currentTimeMillis(),
                    status = status,
                    childId = childId,
                    childName = childName,
                    childAvatar = childAvatar
                )
            )
        }
        saveAll(context, all)
    }

    private fun saveAll(context: Context, list: List<ExtraTimeRequest>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (req in list) {
            val obj = JSONObject().apply {
                put("id", req.id)
                put("packageName", req.packageName)
                put("appName", req.appName)
                put("requestedMinutes", req.requestedMinutes)
                put("reason", req.reason)
                put("timestamp", req.timestamp)
                put("status", req.status)
                put("childId", req.childId)
                put("childName", req.childName)
                put("childAvatar", req.childAvatar)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_REQUESTS, array.toString()).apply()
    }
}
