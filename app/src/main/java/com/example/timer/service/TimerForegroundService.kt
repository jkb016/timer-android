package com.example.timer.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.timer.MainActivity
import com.example.timer.data.ActiveTimerState
import com.example.timer.data.TimerRepository
import com.example.timer.data.TimerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimerForegroundService : Service() {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateChangeListener: (() -> Unit)? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): TimerForegroundService = this@TimerForegroundService
    }

    fun setStateChangeListener(listener: (() -> Unit)?) {
        this.stateChangeListener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMedia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TIMER) {
            handleStopAction()
            return START_STICKY
        }
        if (intent?.action == ACTION_ADD_TIME) {
            val secondsToAdd = intent.getLongExtra(EXTRA_ADD_SECONDS, 60L)
            handleAddTimeAction(secondsToAdd)
            return START_STICKY
        }
        refreshServiceState()
        return START_STICKY
    }

    private fun handleStopAction() {
        stopAlarm()
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val finishedTimers = activeTimers.filter { it.status == TimerStatus.FINISHED }

        val updated = if (finishedTimers.isNotEmpty()) {
            activeTimers.filterNot { it.status == TimerStatus.FINISHED }
        } else {
            activeTimers.map { if (it.status == TimerStatus.RUNNING) it.copy(status = TimerStatus.PAUSED) else it }
        }

        repository.saveActiveTimers(updated)
        stateChangeListener?.invoke()
        refreshServiceState()
    }

    private fun handleAddTimeAction(secondsToAdd: Long) {
        stopAlarm()
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val finishedTimers = activeTimers.filter { it.status == TimerStatus.FINISHED }

        val now = System.currentTimeMillis()
        val millisToAdd = secondsToAdd * 1000L

        val updated = if (finishedTimers.isNotEmpty()) {
            activeTimers.map { timer ->
                if (timer.status == TimerStatus.FINISHED) {
                    timer.copy(
                        currentTotalSeconds = secondsToAdd,
                        remainingMillis = millisToAdd,
                        status = TimerStatus.RUNNING,
                        endTimeMillis = now + millisToAdd
                    )
                } else timer
            }
        } else {
            activeTimers.map { timer ->
                if (timer.status == TimerStatus.RUNNING) {
                    timer.copy(
                        currentTotalSeconds = timer.currentTotalSeconds + secondsToAdd,
                        remainingMillis = timer.remainingMillis + millisToAdd,
                        endTimeMillis = timer.endTimeMillis + millisToAdd
                    )
                } else timer
            }
        }

        repository.saveActiveTimers(updated)
        stateChangeListener?.invoke()
        refreshServiceState()
    }

    fun refreshServiceState() {
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val runningTimers = activeTimers.filter { it.status == TimerStatus.RUNNING }.sortedBy { it.endTimeMillis }
        val finishedTimers = activeTimers.filter { it.status == TimerStatus.FINISHED }

        when {
            finishedTimers.isNotEmpty() -> {
                val firstFinished = finishedTimers.first()
                val labelStr = if (firstFinished.label.isNotBlank()) firstFinished.label else ActiveTimerState.formatSeconds(firstFinished.originalSeconds)
                val title = if (finishedTimers.size == 1) "Timer Finished! ($labelStr)" else "${finishedTimers.size} Timers Finished!"
                val content = if (runningTimers.isNotEmpty()) {
                    "Time's up! (${runningTimers.size} still running)"
                } else {
                    "Time's up!"
                }

                startForegroundNotification(title, content, endTimeMillis = 0L, isCountingDown = false, isAlarm = true)
                triggerAlarm()

                if (runningTimers.isNotEmpty()) {
                    val nextTimer = runningTimers.first()
                    scheduleAlarmManager(nextTimer.endTimeMillis)
                    startTickerLoop()
                } else {
                    stopTickerLoop()
                }
            }
            runningTimers.isNotEmpty() -> {
                stopAlarm()
                val first = runningTimers.first()
                val labelStr = if (first.label.isNotBlank()) first.label else ActiveTimerState.formatSeconds(first.originalSeconds)
                val title = if (runningTimers.size == 1) "$labelStr Timer" else "${runningTimers.size} Timers Running"
                val now = System.currentTimeMillis()
                val remMillis = (first.endTimeMillis - now).coerceAtLeast(0L)
                val content = ActiveTimerState.formatSeconds((remMillis + 999L) / 1000L)

                startForegroundNotification(title, content, endTimeMillis = first.endTimeMillis, isCountingDown = true, isAlarm = false)
                scheduleAlarmManager(first.endTimeMillis)
                startTickerLoop()
            }
            else -> {
                stopAlarm()
                stopTickerLoop()
                cancelAlarmManager()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startTickerLoop() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (true) {
                delay(1000)
                val repository = TimerRepository(applicationContext)
                val activeTimers = repository.getActiveTimers()
                val runningTimers = activeTimers.filter { it.status == TimerStatus.RUNNING }.sortedBy { it.endTimeMillis }
                val finishedTimers = activeTimers.filter { it.status == TimerStatus.FINISHED }

                if (finishedTimers.isNotEmpty() || runningTimers.isEmpty()) {
                    refreshServiceState()
                    break
                }

                val first = runningTimers.first()
                val labelStr = if (first.label.isNotBlank()) first.label else ActiveTimerState.formatSeconds(first.originalSeconds)
                val title = if (runningTimers.size == 1) "$labelStr Timer" else "${runningTimers.size} Timers Running"
                val now = System.currentTimeMillis()
                val remMillis = (first.endTimeMillis - now).coerceAtLeast(0L)
                val content = ActiveTimerState.formatSeconds((remMillis + 999L) / 1000L)

                updateNotification(title, content, endTimeMillis = first.endTimeMillis, isCountingDown = true, isAlarm = false)
            }
        }
    }

    private fun stopTickerLoop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun scheduleAlarmManager(triggerAtMillis: Long) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerForegroundService::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getService(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(this, com.example.timer.AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null
        val showPendingIntent = PendingIntent.getActivity(
            this, 1005, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
                alarmManager.setAlarmClock(clockInfo, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e2: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
    }

    private fun cancelAlarmManager() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerForegroundService::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getService(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun setupMedia() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun playAlarmSound() {
        if (mediaPlayer?.isPlaying == true || ringtone?.isPlaying == true) return
        val soundUri = android.net.Uri.parse("android.resource://" + packageName + "/" + com.example.timer.R.raw.sound)

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, soundUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_ALARM)
                }
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            try {
                mediaPlayer = MediaPlayer.create(applicationContext, com.example.timer.R.raw.sound)?.apply {
                    isLooping = true
                    start()
                }
            } catch (e2: Exception) {
                // Ignore
            }
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null

        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        ringtone = null
    }

    fun startForegroundNotification(
        title: String,
        content: String,
        endTimeMillis: Long = 0L,
        isCountingDown: Boolean = false,
        isAlarm: Boolean = false
    ) {
        val notification = createNotification(title, content, endTimeMillis, isCountingDown, isAlarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(
        title: String,
        content: String,
        endTimeMillis: Long = 0L,
        isCountingDown: Boolean = false,
        isAlarm: Boolean = false
    ) {
        val notification = createNotification(title, content, endTimeMillis, isCountingDown, isAlarm)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "TimerApp:AlarmWakeLock"
        ).apply {
            acquire(60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        wakeLock = null
    }

    fun triggerAlarm() {
        acquireWakeLock()
        playAlarmSound()

        val repository = TimerRepository(applicationContext)
        val finishedTimers = repository.getActiveTimers().filter { it.status == TimerStatus.FINISHED }
        val firstName = if (finishedTimers.isNotEmpty()) {
            finishedTimers.first().displayOriginalLabel
        } else "Timer"

        try {
            val alarmIntent = Intent(this, com.example.timer.AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(com.example.timer.AlarmActivity.EXTRA_TIMER_NAME, firstName)
            }
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } else null
            startActivity(alarmIntent, options)
        } catch (e: Exception) {
            try {
                val alarmIntent = Intent(this, com.example.timer.AlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(com.example.timer.AlarmActivity.EXTRA_TIMER_NAME, firstName)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 1004, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send()
            } catch (e2: Exception) {
                // Fallback
            }
        }

        vibrator?.let {
            if (it.hasVibrator()) {
                val pattern = longArrayOf(0, 500, 500, 500, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, 0)
                }
            }
        }
    }

    fun stopAlarm() {
        stopAlarmSound()
        vibrator?.cancel()
        releaseWakeLock()
        val stopBroadcast = Intent(ACTION_ALARM_STOPPED).apply {
            setPackage(packageName)
        }
        sendBroadcast(stopBroadcast)
    }

    fun stopForegroundService() {
        stopAlarm()
        stopTickerLoop()
        cancelAlarmManager()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(
        title: String,
        content: String,
        endTimeMillis: Long = 0L,
        isCountingDown: Boolean = false,
        isAlarm: Boolean = false
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TimerForegroundService::class.java).apply {
            action = ACTION_STOP_TIMER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1003, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(false)
            .setShowWhen(false)
            .setSound(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val lowChannel = NotificationChannel(
                CHANNEL_ID,
                "Timer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for active timers"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
            }
            manager.createNotificationChannel(lowChannel)

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Timer Alarm Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alarm alerts for finished timers"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(null, null)
            }
            manager.createNotificationChannel(alarmChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        stopTickerLoop()
    }

    companion object {
        const val EXTRA_RESTORE_ON_BOOT = "extra_restore_on_boot"
        const val ACTION_ALARM_TRIGGER = "com.example.timer.ACTION_ALARM_TRIGGER"
        const val ACTION_STOP_TIMER = "com.example.timer.ACTION_STOP_TIMER"
        const val ACTION_ALARM_STOPPED = "com.example.timer.ACTION_ALARM_STOPPED"
        const val ACTION_ADD_TIME = "com.example.timer.ACTION_ADD_TIME"
        const val EXTRA_ADD_SECONDS = "extra_add_seconds"
        private const val CHANNEL_ID = "timer_service_channel"
        private const val CHANNEL_ALARM_ID = "timer_alarm_channel_v3"
        private const val NOTIFICATION_ID = 1001
    }
}
