package nethical.digipaws.blockers

import android.os.SystemClock
import android.util.Log
import nethical.digipaws.ui.activity.TimedActionActivity
import nethical.digipaws.utils.TimeTools
import java.util.Calendar

class AppBlocker:BaseBlocker() {

    // package-name -> end-time-in-millis
    private var cooldownAppsList:MutableMap<String,Long> = mutableMapOf()

    // package-name -> [(start-time, end-time), ...]
    private var cheatHours: MutableMap<String, List<Pair<Int, Int>>> = mutableMapOf()

    var blockedAppsList = hashSetOf("")

    fun doesAppNeedToBeBlocked(packageName: String): AppBlockerResult {

        if(cooldownAppsList.containsKey(packageName)){
            // check if app has surpassed the cooldown period
            if (cooldownAppsList[packageName]!! < SystemClock.uptimeMillis()){
                removeCooldownFrom(packageName)
                return AppBlockerResult(isBlocked = true)
            }

            // app is still under cooldown
            return AppBlockerResult(
                isBlocked = false,
                cooldownEndTime = cooldownAppsList[packageName]!!
            )
        }

        // check if app is under cheat-hours
        val endCheatMillis = getEndTimeInMillis(packageName)
        if (endCheatMillis != null) {
            return AppBlockerResult(isBlocked = false, cheatHoursEndTime = endCheatMillis)
        }

        if (blockedAppsList.contains(packageName)) {
            return AppBlockerResult(
                isBlocked = true
            )
        }
        return AppBlockerResult(isBlocked = false)
    }
    fun putCooldownTo(packageName: String, endTime: Long) {
        cooldownAppsList[packageName] = endTime
        Log.d("cooldownAppsList",cooldownAppsList.toString())
    }

    fun removeCooldownFrom(packageName: String) {
        cooldownAppsList.remove(packageName)
    }

    private fun getEndTimeInMillis(packageName: String): Long? {
        if (cheatHours[packageName] == null) return null

        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)

        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)
        val uptimeNow = SystemClock.uptimeMillis()

        cheatHours[packageName]?.forEach { (startMinutes, endMinutes) ->
            if ((startMinutes <= endMinutes && currentMinutes in startMinutes until endMinutes) ||
                (startMinutes > endMinutes && (currentMinutes >= startMinutes || currentMinutes < endMinutes))
            ) {
                var dayOffsetMinutes = 0

                // if cheat hours cross midnight and it is still the first day treat the end time as tomorrow
                if (startMinutes > endMinutes && currentMinutes > endMinutes) {
                    dayOffsetMinutes = 1440
                }

                // Convert endMinutes to uptimeMillis
                val diffMinutes = endMinutes + dayOffsetMinutes - currentMinutes

                Log.d("AppBlocker", "$packageName cheat-hour ends after $diffMinutes minutes")
                val endTimeMillis = uptimeNow + (diffMinutes * 60 * 1000)

                return endTimeMillis
            }
        }
        return null
    }

    fun refreshCheatHoursData(cheatList: List<TimedActionActivity.AutoTimedActionItem>) {
        cheatHours.clear()
        cheatList.forEach { item ->
            val startTime = item.startTimeInMins
            val endTime = item.endTimeInMins
            val packageNames: ArrayList<String> = item.packages

            packageNames.forEach { packageName ->
                Log.d(
                    "AppBlocker",
                    "added cheat-hour data for $packageName : $startTime to $endTime"
                )

                if (cheatHours.containsKey(packageName)) {
                    val cheatHourTimeData: List<Pair<Int, Int>>? = cheatHours[packageName]
                    val cheatHourNewTimeData: MutableList<Pair<Int, Int>> =
                        cheatHourTimeData!!.toMutableList()

                    cheatHourNewTimeData.add(Pair(startTime, endTime))
                    cheatHours[packageName] = cheatHourNewTimeData
                } else {
                    cheatHours[packageName] = listOf(Pair(startTime, endTime))
                }
            }
        }

    }

    data class AppBlockerResult(
        val isBlocked: Boolean,
        val cheatHoursEndTime: Long = -1L,
        val cooldownEndTime: Long = -1L
    )

}