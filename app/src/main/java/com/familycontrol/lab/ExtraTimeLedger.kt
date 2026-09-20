package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AllowanceItem(
    val requestId: String,
    val packageName: String,
    val approvedMinutes: Int,
    val consumedMinutes: Int,
    val remainingMinutes: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L
)

object ExtraTimeLedger {
    private const val PREFS = "extra_time_ledger_v2"

    fun getRemainingExtraMinutes(context: Context, packageName: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("ledger_$packageName", null) ?: return 0
        return try {
            val array = JSONArray(raw)
            var totalRemaining = 0
            val now = System.currentTimeMillis()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val approved = obj.optInt("approved_minutes", 0)
                val consumed = obj.optInt("consumed_minutes", 0)
                val expires = obj.optLong("expires_at", 0L)
                if (expires <= 0 || expires > now) {
                    val remaining = (approved - consumed).coerceAtLeast(0)
                    totalRemaining += remaining
                }
            }
            totalRemaining
        } catch (e: Exception) {
            0
        }
    }

    fun recordConsumption(context: Context, packageName: String, extraMinutesConsumed: Int) {
        if (extraMinutesConsumed <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("ledger_$packageName", null) ?: return
        try {
            val array = JSONArray(raw)
            var toConsume = extraMinutesConsumed
            val updatedArray = JSONArray()
            val now = System.currentTimeMillis()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val approved = obj.optInt("approved_minutes", 0)
                var consumed = obj.optInt("consumed_minutes", 0)
                val expires = obj.optLong("expires_at", 0L)

                if ((expires == 0L || expires > now) && toConsume > 0) {
                    val avail = (approved - consumed).coerceAtLeast(0)
                    val add = minOf(avail, toConsume)
                    consumed += add
                    toConsume -= add
                    obj.put("consumed_minutes", consumed)
                }
                updatedArray.put(obj)
            }
            prefs.edit().putString("ledger_$packageName", updatedArray.toString()).apply()
        } catch (_: Exception) {}
    }

    fun updateFromRemote(context: Context, allowancesJson: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val byPackage = mutableMapOf<String, JSONArray>()

        for (i in 0 until allowancesJson.length()) {
            val obj = allowancesJson.getJSONObject(i)
            val pkg = obj.optString("package_name", "")
            if (pkg.isNotBlank()) {
                val list = byPackage.getOrPut(pkg) { JSONArray() }
                list.put(obj)
            }
        }

        val editor = prefs.edit()
        for ((pkg, array) in byPackage) {
            editor.putString("ledger_$pkg", array.toString())
        }
        editor.apply()
    }

    fun addExtraTime(context: Context, packageName: String, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("ledger_$packageName", "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val obj = JSONObject()
            .put("request_id", "local_piggy_${System.currentTimeMillis()}")
            .put("package_name", packageName)
            .put("approved_minutes", minutes)
            .put("consumed_minutes", 0)
            .put("expires_at", System.currentTimeMillis() + 86400000L)
        array.put(obj)
        prefs.edit().putString("ledger_$packageName", array.toString()).apply()
    }
}
