package mina.infoxus.utils

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import mina.infoxus.ui.fragments.usage.AllAppsUsageFragment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageStatsHelper(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val guardian = UnmatchedCloseEventGuardian()

    fun getForegroundStatsByTimestamps(start: Long, end: Long): List<AllAppsUsageFragment.Stat> {

        val foregroundProcesses = mutableListOf<String>()
        if (end >= System.currentTimeMillis() - 1500) {

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            if (appProcesses != null) {
                for (appProcess in appProcesses) {
                    if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                        foregroundProcesses.add(appProcess.processName)
                    }
                }
            }
        }

        val events = usageStatsManager.queryEvents(start, end)

        val moveToForegroundMap = mutableMapOf<AppClass, Long?>()

        val componentForegroundStats = mutableListOf<ComponentForegroundStat>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val className = event.className ?: continue

            val appClass = AppClass(event.packageName, className)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> {

                    moveToForegroundMap[appClass] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 3 -> {

                    var eventBeginTime: Long? = moveToForegroundMap[appClass]
                    if (eventBeginTime != null) {

                        moveToForegroundMap[appClass] = null
                    } else if (moveToForegroundMap.keys.none { event.packageName == it.packageName } &&
                        guardian.test(event, start)) {

                        eventBeginTime = start
                    } else {

                        continue
                    }

                    val endTime = moveToForegroundMap.entries
                        .filter { event.packageName == it.key.packageName }
                        .filter { it.value != null }
                        .map { it.value!! } 
                        .minOrNull() ?: event.timeStamp

                    componentForegroundStats.add(ComponentForegroundStat(eventBeginTime, endTime, event.packageName))
                }
                UsageEvents.Event.DEVICE_SHUTDOWN -> {

                    for (key in moveToForegroundMap.keys) {
                        val startTime = moveToForegroundMap[key]
                        if (startTime == null) continue 

                        componentForegroundStats.add(ComponentForegroundStat(startTime, event.timeStamp, key.packageName))

                        moveToForegroundMap.keys.filter { key.packageName == it.packageName }.forEach { moveToForegroundMap[it] = null }
                    }
                }
                UsageEvents.Event.DEVICE_STARTUP -> {

                    for (key in moveToForegroundMap.keys) {
                        moveToForegroundMap[key] = null
                    }

                    start.coerceAtLeast(event.timeStamp)
                }
            }
        }

        for (key in moveToForegroundMap.keys) {
            val startTime = moveToForegroundMap[key]
            if (startTime == null) continue 

            for (foregroundProcess in foregroundProcesses) {
                if (foregroundProcess.contains(key.packageName)) {

                    componentForegroundStats.add(ComponentForegroundStat(startTime, minOf(System.currentTimeMillis(), end), key.packageName))
                    break
                }
            }
        }

        if (moveToForegroundMap.isEmpty()) {
            val packageManager = context.packageManager
            for (foregroundProcess in foregroundProcesses) {
                if (packageManager.getLaunchIntentForPackage(foregroundProcess) != null) {
                    componentForegroundStats.add(ComponentForegroundStat(start, minOf(System.currentTimeMillis(), end), foregroundProcess))
                    Log.d("UsageStatsHelper", "Assuming that application $foregroundProcess has been used the whole query time")
                }
            }
        }

        return aggregateForegroundStats(componentForegroundStats)
    }

    fun getForegroundStatsByRelativeDay(offset: Int): List<AllAppsUsageFragment.Stat> {
        val queryDay = LocalDate.now().minusDays(offset.toLong())
        val start = queryDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = queryDay.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getForegroundStatsByTimestamps(start, end)
    }

    fun getForegroundStatsByDay(queryDate: LocalDate): List<AllAppsUsageFragment.Stat> {
        val start = queryDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = queryDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getForegroundStatsByTimestamps(start, end)
    }

    fun getAccurateUsageStatsSince(start: Long, end: Long): Map<String, Long> {
        val usageMap = mutableMapOf<String, Long>()

        // Step 1: queryUsageStats for committed historical data.
        // NOTE: This data is flushed with a delay (up to 30 min), so it's used
        // only as a baseline for the older portion of the window.
        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end
        )
        if (stats != null) {
            for (stat in stats) {
                val pkg = stat.packageName ?: continue
                val total = stat.totalTimeInForeground
                if (total > 0L) {
                    // Use += because INTERVAL_BEST can return multiple buckets
                    // for the same package (e.g. when reset hour spans midnight).
                    usageMap[pkg] = (usageMap[pkg] ?: 0L) + total
                }
            }
        }

        // Step 2: queryEvents for real-time recent data.
        // Events are committed IMMEDIATELY (unlike queryUsageStats which is delayed).
        // We reconstruct sessions from events for the last 2 hours, then override
        // the corresponding portion in usageMap with the more accurate event data.
        val recentWindowStart = maxOf(start, end - 2 * 60 * 60 * 1000L)
        try {
            val eventUsage = reconstructUsageFromEvents(recentWindowStart, end)

            // For each package tracked via events, calculate how much the
            // event-based time exceeds the stats-based time in the recent window.
            // We use events as the source of truth for the recent window.
            for ((pkg, eventMs) in eventUsage) {
                val statsMs = usageMap[pkg] ?: 0L
                // If events show MORE time than committed stats, use the event data.
                // Events are always more accurate for recent sessions.
                if (eventMs > statsMs) {
                    usageMap[pkg] = eventMs
                }
            }
        } catch (e: Exception) {
            Log.e("UsageStatsHelper", "Error reconstructing recent usage from events", e)
        }

        return usageMap
    }

    /**
     * Reconstructs foreground usage by package using raw UsageEvents.
     * Tracks at PACKAGE level (not activity level) so it works for all apps.
     * Events are real-time and don't have the flush delay of queryUsageStats.
     */
    private fun reconstructUsageFromEvents(start: Long, end: Long): Map<String, Long> {
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()

        // packageName -> timestamp when it last came to foreground
        val foregroundSince = mutableMapOf<String, Long>()
        // packageName -> total accumulated ms
        val accumulated = mutableMapOf<String, Long>()
        // packages that were already in foreground before `start`
        val preStartForeground = mutableSetOf<String>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 1 -> {
                    // App came to foreground
                    if (!foregroundSince.containsKey(pkg)) {
                        foregroundSince[pkg] = maxOf(start, time)
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED,
                2, 23, 24 -> {
                    // App went to background
                    val since = foregroundSince.remove(pkg)
                    if (since != null) {
                        val duration = time - since
                        if (duration > 0) {
                            accumulated[pkg] = (accumulated[pkg] ?: 0L) + duration
                        }
                    } else if (!preStartForeground.contains(pkg)) {
                        // No RESUMED seen → app was already in foreground before `start`
                        preStartForeground.add(pkg)
                        val duration = time - start
                        if (duration > 0) {
                            accumulated[pkg] = (accumulated[pkg] ?: 0L) + duration
                        }
                    }
                }
            }
        }

        // Any package still in foregroundSince is currently open
        val now = System.currentTimeMillis()
        for ((pkg, since) in foregroundSince) {
            val duration = minOf(now, end) - since
            if (duration > 0) {
                accumulated[pkg] = (accumulated[pkg] ?: 0L) + duration
            }
        }

        return accumulated
    }



    private fun aggregateForegroundStats(foregroundStats: List<ComponentForegroundStat>): List<AllAppsUsageFragment.Stat> {
        val usageStats = mutableListOf<AllAppsUsageFragment.Stat>()
        if (foregroundStats.isEmpty()) return usageStats

        val applicationTotalForegroundTime = mutableMapOf<String, Long>()

        val applicationStartTimes = mutableMapOf<String, MutableList<ZonedDateTime>>()

        for (foregroundStat in foregroundStats) {

            applicationTotalForegroundTime[foregroundStat.packageName] =
                applicationTotalForegroundTime.getOrDefault(foregroundStat.packageName, 0) +
                        (foregroundStat.endTime - foregroundStat.beginTime)

            val startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(foregroundStat.beginTime),
                ZoneId.systemDefault()
            )
            applicationStartTimes.getOrPut(foregroundStat.packageName) { mutableListOf() }.add(startTime)
        }

        for ((packageName, totalTime) in applicationTotalForegroundTime) {
            val startTimes = applicationStartTimes[packageName] ?: listOf()
            usageStats.add(AllAppsUsageFragment.Stat(packageName, totalTime, startTimes))
        }

        return usageStats.sortedByDescending { it.totalTime }
    }

    private class AppClass(val packageName: String, val className: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AppClass
            if (packageName != other.packageName) return false
            if (className != other.className) return false
            return true
        }

        override fun hashCode(): Int {
            var result = packageName.hashCode()
            result = 31 * result + className.hashCode()
            return result
        }
    }

    private class ComponentForegroundStat(val beginTime: Long, val endTime: Long, val packageName: String)

    private class UnmatchedCloseEventGuardian {
        fun test(event: UsageEvents.Event, start: Long): Boolean {

            return true
        }
    }

    fun getDefaultLauncherPackageName(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }
}
