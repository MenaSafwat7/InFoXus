package mina.infoxus.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.CountDownTimer
import androidx.core.app.NotificationCompat

class NotificationTimerManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "TimerNotificationChannel"
        private const val NOTIFICATION_ID = 1001
    }

    private var countDownTimer: CountDownTimer? = null
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Timer Notifications",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Timer progress notifications"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun startTimer(
        totalMillis: Long,
        isCountdown: Boolean = true,
        onTickCallback: ((Long) -> Unit)? = null,
        onFinishCallback: (() -> Unit)? = null
    ) {

        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val displayMillis =
                    if (isCountdown) millisUntilFinished else totalMillis - millisUntilFinished

                updateTimerNotification(displayMillis)

                onTickCallback?.invoke(displayMillis)
            }

            override fun onFinish() {


                onFinishCallback?.invoke()
            }
        }.start()
    }

    private fun updateTimerNotification(remainingMillis: Long) {
    }

    fun stopTimer() {
        countDownTimer?.cancel()
    }
}

