package mina.infoxus.blockers

import android.os.SystemClock
import android.util.Log
import mina.infoxus.Constants
import mina.infoxus.ui.activity.TimedActionActivity
import mina.infoxus.utils.TimeTools
import java.util.Calendar

class FocusModeBlocker : BaseBlocker() {

    private var autoFocusHours: MutableMap<String, List<Pair<Int, Int>>> = mutableMapOf()

    var focusModeData = FocusModeData()

    fun doesAppNeedToBeBlocked(packageName: String): FocusModeResult {

        if (focusModeData.isTurnedOn) {
            if (focusModeData.endTime < System.currentTimeMillis()) {
                focusModeData.isTurnedOn = false
                return FocusModeResult(isBlocked = false, isRequestingToUpdateSPData = true)
            }
            when (focusModeData.modeType) {
                Constants.FOCUS_MODE_BLOCK_SELECTED -> {
                    if (focusModeData.selectedApps.contains(packageName)) {
                        return FocusModeResult(
                            isBlocked = true,
                            focusModeEndTime = focusModeData.endTime
                        )
                    }
                }

                Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED -> {
                    if (!focusModeData.selectedApps.contains(packageName)) {
                        return FocusModeResult(
                            isBlocked = true,
                            focusModeEndTime = focusModeData.endTime
                        )
                    }
                }
            }
        }

        val endAutoFocus = getEndTimeInMillis(packageName)
        if (endAutoFocus != null) {
            return FocusModeResult(isBlocked = true, focusModeEndTime = endAutoFocus)
        }

        return FocusModeResult(isBlocked = false)
    }

    private fun getEndTimeInMillis(packageName: String): Long? {
        if (autoFocusHours[packageName] == null) return null

        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)

        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)
        val uptimeNow = SystemClock.uptimeMillis()

        autoFocusHours[packageName]?.forEach { (startMinutes, endMinutes) ->
            if ((startMinutes <= endMinutes && currentMinutes in startMinutes until endMinutes) ||
                (startMinutes > endMinutes && (currentMinutes >= startMinutes || currentMinutes < endMinutes))
            ) {

                val diffMinutes = endMinutes - currentMinutes
                val endTimeMillis = uptimeNow + (diffMinutes * 60 * 1000)

                return endTimeMillis
            }
        }
        return null
    }

    fun refreshCheatHoursData(focusData: List<TimedActionActivity.AutoTimedActionItem>) {
        autoFocusHours.clear()
        focusData.forEach { item ->
            val startTime = item.startTimeInMins
            val endTime = item.endTimeInMins
            val packageNames: ArrayList<String> = item.packages

            packageNames.forEach { packageName ->

                if (autoFocusHours.containsKey(packageName)) {
                    val cheatHourTimeData: List<Pair<Int, Int>>? = autoFocusHours[packageName]
                    val cheatHourNewTimeData: MutableList<Pair<Int, Int>> =
                        cheatHourTimeData!!.toMutableList()

                    cheatHourNewTimeData.add(Pair(startTime, endTime))
                    autoFocusHours[packageName] = cheatHourNewTimeData
                } else {
                    autoFocusHours[packageName] = listOf(Pair(startTime, endTime))
                }
            }
        }
        Log.d("FocusModeBlocker", "Auto Focus Data updated $autoFocusHours")

    }

    data class FocusModeData(
        var isTurnedOn: Boolean = false,
        val endTime: Long = -1,
        val modeType: Int = Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED,
        var selectedApps: HashSet<String> = hashSetOf()
    )

    data class FocusModeResult(
        val isBlocked: Boolean,
        val focusModeEndTime: Long = -1,
        val isRequestingToUpdateSPData: Boolean = false
    )

}
