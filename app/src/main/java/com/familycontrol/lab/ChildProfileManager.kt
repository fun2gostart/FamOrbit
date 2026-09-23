package com.familycontrol.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

data class ChildProfile(
    val id: String,
    val name: String,
    val avatarEmoji: String = "👶",
    val dailyScreenLimitMinutes: Int = 180,
    val pairingCode: String? = null,
    val deviceCount: Int = 1,
    val isDefault: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatarEmoji", avatarEmoji)
        put("dailyScreenLimitMinutes", dailyScreenLimitMinutes)
        putOpt("pairingCode", pairingCode)
        put("deviceCount", deviceCount)
        put("isDefault", isDefault)
    }

    companion object {
        fun fromJson(json: JSONObject): ChildProfile {
            return ChildProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                avatarEmoji = json.optString("avatarEmoji", "👶"),
                dailyScreenLimitMinutes = json.optInt("dailyScreenLimitMinutes", 180),
                pairingCode = json.optString("pairingCode").takeIf { !it.isNullOrBlank() },
                deviceCount = json.optInt("deviceCount", 1),
                isDefault = json.optBoolean("isDefault", false)
            )
        }
    }
}

object ChildProfileManager {
    private const val PREFS = "family_control_children_prefs"
    private const val KEY_CHILDREN = "children_profiles"
    private const val KEY_ACTIVE_CHILD_ID = "active_child_id"

    @Synchronized
    fun getChildren(context: Context): List<ChildProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CHILDREN, null)
        if (raw.isNullOrBlank()) {
            return migrateOrSeedInitial(context)
        }
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<ChildProfile>()
            val allDevices = ChildDeviceManager.getAllDevices(context)
            for (i in 0 until array.length()) {
                val profile = ChildProfile.fromJson(array.getJSONObject(i))
                val count = allDevices.count { it.childId == profile.id }
                val updatedProfile = profile.copy(deviceCount = count)
                list.add(updatedProfile)
            }
            if (list.isEmpty()) migrateOrSeedInitial(context) else list
        } catch (e: Exception) {
            migrateOrSeedInitial(context)
        }
    }

    @Synchronized
    private fun saveChildren(context: Context, children: List<ChildProfile>) {
        val array = JSONArray()
        children.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHILDREN, array.toString())
            .apply()
    }

    @Synchronized
    fun getActiveChild(context: Context): ChildProfile {
        val children = getChildren(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_CHILD_ID, null)
        val found = children.find { it.id == activeId }
        if (found != null) return found

        val fallback = children.firstOrNull() ?: migrateOrSeedInitial(context).first()
        setActiveChild(context, fallback.id)
        return fallback
    }

    @Synchronized
    fun setActiveChild(context: Context, childId: String): ChildProfile {
        val children = getChildren(context)
        val profile = children.find { it.id == childId } ?: children.first()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CHILD_ID, profile.id)
            .apply()

        // Sync with ApiClient and App preferences
        ApiClient.setServerChildId(context, profile.id)
        context.getSharedPreferences("family_control_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("child_display_name", profile.name)
            .apply()

        EventLog.record(context, "CHILD_PROFILE_ACTIVATED id=${profile.id} name=${profile.name}")
        return profile
    }

    @Synchronized
    fun updateChild(context: Context, updated: ChildProfile) {
        val current = getChildren(context).toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            current[index] = updated
            saveChildren(context, current)
            if (getActiveChild(context).id == updated.id) {
                context.getSharedPreferences("family_control_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("child_display_name", updated.name)
                    .apply()
            }
        }
    }

    @Synchronized
    fun removeChild(context: Context, childId: String): Boolean {
        val current = getChildren(context).toMutableList()
        if (current.size <= 1) return false // Do not delete last remaining child

        val removed = current.removeAll { it.id == childId }
        if (removed) {
            saveChildren(context, current)
            // Clean up devices belonging to this child
            try {
                val devices = ChildDeviceManager.getDevicesForChild(context, childId)
                devices.forEach { dev ->
                    ChildDeviceManager.removeDevice(context, childId, dev.deviceId)
                }
            } catch (_: Exception) {}

            val active = getActiveChild(context)
            if (active.id == childId) {
                setActiveChild(context, current.first().id)
            }
            EventLog.record(context, "CHILD_PROFILE_REMOVED id=$childId")
        }
        return removed
    }

    fun addChild(
        context: Context,
        name: String,
        avatarEmoji: String,
        dailyScreenLimitMinutes: Int,
        onComplete: (ChildProfile?, String?) -> Unit
    ) {
        thread {
            try {
                val familyId = ApiClient.serverFamilyId(context)
                var cloudChildId: String? = null
                var pairingCode: String? = null

                if (!familyId.isNullOrBlank()) {
                    // Register with Render Cloud API
                    val regRes = ApiClient.registerChild(context, name)
                    if (regRes.ok) {
                        cloudChildId = try {
                            JSONObject(regRes.body).getString("child_id")
                        } catch (_: Exception) { null }
                    }

                    if (!cloudChildId.isNullOrBlank()) {
                        ApiClient.createServerPolicyForChild(context, familyId, cloudChildId, name.trim(), dailyScreenLimitMinutes)
                        val pairRes = ApiClient.generatePairingCodeForChild(context, cloudChildId)
                        if (pairRes.ok) {
                            pairingCode = pairRes.body
                        }
                    }
                }

                // Fallback ID if cloud is unreachable
                val finalId = cloudChildId ?: "child_${UUID.randomUUID().toString().take(8)}"
                val finalPairingCode = pairingCode ?: generateLocalPairingCode()

                val newProfile = ChildProfile(
                    id = finalId,
                    name = name.trim(),
                    avatarEmoji = avatarEmoji,
                    dailyScreenLimitMinutes = dailyScreenLimitMinutes,
                    pairingCode = finalPairingCode,
                    deviceCount = 0,
                    isDefault = false
                )

                synchronized(this) {
                    val current = getChildren(context).toMutableList()
                    current.add(newProfile)
                    saveChildren(context, current)
                }

                EventLog.record(context, "CHILD_PROFILE_CREATED id=$finalId name=$name")
                onComplete(newProfile, null)
            } catch (e: Exception) {
                onComplete(null, e.message ?: "Failed to create child profile")
            }
        }
    }

    private fun generateLocalPairingCode(): String {
        val rand = (100000..999999).random().toString()
        return "${rand.substring(0, 3)}-${rand.substring(3)}"
    }

    @Synchronized
    private fun migrateOrSeedInitial(context: Context): List<ChildProfile> {
        val existingChildId = ApiClient.serverChildId(context) ?: "child_default"
        val existingName = context.getSharedPreferences("family_control_prefs", Context.MODE_PRIVATE)
            .getString("child_display_name", "Dummy Profile") ?: "Dummy Profile"

        val defaultProfile = ChildProfile(
            id = existingChildId,
            name = if (existingName == "Alex" || existingName == "Child") "Dummy Profile" else existingName,
            avatarEmoji = "👶",
            dailyScreenLimitMinutes = 180,
            pairingCode = ApiClient.getPairingCode(context),
            deviceCount = 0,
            isDefault = true
        )

        val list = listOf(defaultProfile)
        saveChildren(context, list)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CHILD_ID, defaultProfile.id)
            .apply()

        return list
    }
}
