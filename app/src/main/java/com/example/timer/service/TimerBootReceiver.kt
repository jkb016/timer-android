package com.example.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.timer.data.TimerRepository
import com.example.timer.data.TimerStatus

class TimerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (isBootAction) {
            val repository = TimerRepository(context)
            val activeTimers = repository.getActiveTimers()

            val hasActiveOrFinished = activeTimers.any {
                it.status == TimerStatus.RUNNING || it.status == TimerStatus.FINISHED
            }

            if (hasActiveOrFinished) {
                val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                    putExtra(TimerForegroundService.EXTRA_RESTORE_ON_BOOT, true)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}

