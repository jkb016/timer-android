package com.jkb016.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jkb016.timer.AlarmActivity
import com.jkb016.timer.data.TimerRepository
import com.jkb016.timer.data.TimerStatus

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or
            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
            android.os.PowerManager.ON_AFTER_RELEASE,
            "TimerApp:AlarmReceiverWakeLock"
        ).apply {
            acquire(15 * 1000L)
        }

        val repository = TimerRepository(context)
        val activeTimers = repository.getActiveTimers()
        val runningTimers = activeTimers.filter { it.status == TimerStatus.RUNNING }.sortedBy { it.endTimeMillis }
        val now = System.currentTimeMillis()
        // Fallback for a slightly early alarm: finish the next running timer, but only if nothing
        // has finished yet, otherwise a second pass would wrongly finish the following timer too.
        val fallbackId = if (activeTimers.none { it.status == TimerStatus.FINISHED }) runningTimers.firstOrNull()?.id else null

        val updated = activeTimers.map { timer ->
            if (timer.status == TimerStatus.RUNNING && (timer.endTimeMillis <= now + 2000L || timer.id == fallbackId)) {
                timer.copy(remainingMillis = 0L, status = TimerStatus.FINISHED)
            } else timer
        }
        repository.saveActiveTimers(updated)

        val finishedTimers = updated.filter { it.status == TimerStatus.FINISHED }
        val firstName = if (finishedTimers.isNotEmpty()) {
            finishedTimers.first().displayOriginalLabel
        } else "Timer"

        // 1. Start foreground service to trigger alarm sound, vibration, and full-screen intent
        val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
            action = TimerForegroundService.ACTION_ALARM_TRIGGER
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            try {
                context.startService(serviceIntent)
            } catch (e2: Exception) {
                // Ignore
            }
        }

        // 2. Launch AlarmActivity directly over lock screen with background activity launch authorization
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(AlarmActivity.EXTRA_TIMER_NAME, firstName)
        }
        val creatorOptions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            1004,
            alarmIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            creatorOptions
        )

        val senderOptions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        try {
            pendingIntent.send(context, 0, null, null, null, null, senderOptions)
        } catch (e: Exception) {
            try {
                context.startActivity(alarmIntent)
            } catch (e2: Exception) {
                // Ignore
            }
        }
    }
}
