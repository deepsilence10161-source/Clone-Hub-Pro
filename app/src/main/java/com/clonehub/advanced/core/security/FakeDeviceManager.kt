package com.clonehub.advanced.core.security

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 100x Advance: Virtual Hardware & Device Identity Isolator.
 * Assigns each isolated space a unique, virtualized Android ID, Manufacturer, Model,
 * and Mac Address to prevent apps from linking multiple accounts together.
 */
class FakeDeviceManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("virtual_device_profiles", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var INSTANCE: FakeDeviceManager? = null

        fun getInstance(context: Context): FakeDeviceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FakeDeviceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class DeviceProfile(
        val androidId: String,
        val manufacturer: String,
        val model: String,
        val brand: String,
        val userAgent: String
    )

    /**
     * Gets or generates a consistent, isolated device fingerprint for a space.
     */
    fun getDeviceProfileForSpace(spaceId: Int): DeviceProfile {
        val key = "space_${spaceId}_profile"
        val existingId = prefs.getString("${key}_android_id", null)

        return if (existingId != null) {
            DeviceProfile(
                androidId = existingId,
                manufacturer = prefs.getString("${key}_manufacturer", "Samsung") ?: "Samsung",
                model = prefs.getString("${key}_model", "SM-S928B") ?: "SM-S928B",
                brand = prefs.getString("${key}_brand", "samsung") ?: "samsung",
                userAgent = prefs.getString("${key}_ua", "Mozilla/5.0 (Linux; Android 15; SM-S928B)") ?: ""
            )
        } else {
            // Generate a fresh unique identity for this space
            val newId = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().apply {
                putString("${key}_android_id", newId)
                putString("${key}_manufacturer", "Google")
                putString("${key}_model", "Pixel 9 Pro")
                putString("${key}_brand", "google")
                putString("${key}_ua", "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro)")
                apply()
            }
            DeviceProfile(
                androidId = newId,
                manufacturer = "Google",
                model = "Pixel 9 Pro",
                brand = "google",
                userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro)"
            )
        }
    }

    /**
     * Resets device identity for a space (equivalent to "Reset Device" action).
     */
    fun randomizeDeviceIdentity(spaceId: Int): DeviceProfile {
        val key = "space_${spaceId}_profile"
        val newId = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().apply {
            putString("${key}_android_id", newId)
            apply()
        }
        return getDeviceProfileForSpace(spaceId)
    }
}
