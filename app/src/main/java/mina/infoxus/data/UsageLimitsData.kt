package mina.infoxus.data

import java.util.UUID
import mina.infoxus.utils.TimeTools

data class AppUsageLimit(
    val id: String = UUID.randomUUID().toString(),
    val packageNames: Set<String>,
    val name: String,
    val timeLimitMinutes: Int,
    var currentUsageMinutes: Float = 0f,
    var baselineUsageMinutes: Float = 0f, // New field for location-based baseline
    var appUsageMap: Map<String, Float> = emptyMap(), // Tracks usage per package
    val isLocationRestricted: Boolean = false,
    val locationId: String? = null,
    val locationName: String? = null,
    var lastResetDate: String = TimeTools.getCurrentDate(),
    var enabled: Boolean = true
) {
    fun isLimitExceeded(): Boolean {
        return enabled && currentUsageMinutes >= timeLimitMinutes
    }
}

data class UsageLimitsConfig(
    val limits: List<AppUsageLimit> = emptyList(),
    val resetHour: Int = 5,
    val resetMinute: Int = 0
)
