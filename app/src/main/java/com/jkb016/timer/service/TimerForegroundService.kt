package com.jkb016.timer.service

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
import com.jkb016.timer.MainActivity
import com.jkb016.timer.data.ActiveTimerState
import com.jkb016.timer.data.TimerRepository
import com.jkb016.timer.data.TimerStatus
import com.jkb016.timer.ui.TimerViewModel
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
    private var alarmScreenShown = false
    private var alarmLaunchCheckJob: Job? = null

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
        val initialNotification = createNotification("Timer", "Running...", isCountingDown = false, isAlarm = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ALARM_SHOWN) {
            // Red screen is visible: swap the heads-up alarm notification for a silent one.
            alarmScreenShown = true
            alarmLaunchCheckJob?.cancel()
        }
        if (intent?.action == ACTION_ALARM_HIDDEN) {
            // Red screen left while still ringing: bring the heads-up back. Skip when the screen is
            // off, otherwise the full-screen intent would wake the device right after power is pressed.
            // Also skip while locked: launching over the keyguard briefly stops/restarts the activity
            // as the screen turns on, and there's nothing else the user can switch to from there.
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
                alarmScreenShown = false
            }
        }

        // Android requires startForeground() to be actively called within seconds of startForegroundService().
        // Always ensure a foreground notification is immediately posted.
        ensureForegroundNotification()

        if (intent?.action == ACTION_ALARM_SHOWN || intent?.action == ACTION_ALARM_HIDDEN) {
            // ensureForegroundNotification() above already re-posted it; don't re-trigger the alarm.
            return START_STICKY
        }
        if (intent?.action == ACTION_ALARM_TRIGGER) {
            handleAlarmTriggerAction()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_TIMER) {
            handleStopAction()
            return START_STICKY
        }
        if (intent?.action == ACTION_ADD_TIME) {
            val secondsToAdd = intent.getLongExtra(EXTRA_ADD_SECONDS, 60L)
            handleAddTimeAction(secondsToAdd)
            return START_STICKY
        }
        if (intent?.action == ACTION_REMOVE_TIMER) {
            intent.getStringExtra(EXTRA_TIMER_ID)?.let { handleRemoveTimerAction(it) }
            return START_STICKY
        }
        refreshServiceState()
        return START_STICKY
    }

    private fun ensureForegroundNotification() {
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val finishedTimers = activeTimers.filter { it.status == TimerStatus.FINISHED }
        val runningTimers = activeTimers.filter { it.status == TimerStatus.RUNNING }.sortedBy { it.endTimeMillis }

        when {
            finishedTimers.isNotEmpty() -> {
                val label = finishedTimers.first().displayOriginalLabel
                startForegroundNotification(label, "Time's up!", timerId = finishedTimers.first().id, isCountingDown = false, isAlarm = true)
            }
            runningTimers.isNotEmpty() -> {
                val nextTimer = runningTimers.first()
                val labelStr = if (nextTimer.label.isNotBlank()) nextTimer.label else ActiveTimerState.formatSeconds(nextTimer.originalSeconds)
                val title = if (runningTimers.size == 1) "$labelStr Timer" else "${runningTimers.size} Timers Running"
                val now = System.currentTimeMillis()
                val remMillis = (nextTimer.endTimeMillis - now).coerceAtLeast(0L)
                val content = ActiveTimerState.formatSeconds((remMillis + 999L) / 1000L)
                startForegroundNotification(title, content, timerId = nextTimer.id, endTimeMillis = nextTimer.endTimeMillis, isCountingDown = true, isAlarm = false)
            }
            else -> {
                val initialNotification = createNotification("Timer", "Running...", isCountingDown = false, isAlarm = false)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            initialNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, initialNotification)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun handleAlarmTriggerAction() {
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val runningTimers = activeTimers.filter { it.status == TimerStatus.RUNNING }.sortedBy { it.endTimeMillis }
        val now = System.currentTimeMillis()
        // TimerAlarmReceiver has usually finished the due timer already before starting us; only
        // fall back to the next running timer when nothing is finished, or we'd finish a second one.
        val fallbackId = if (activeTimers.none { it.status == TimerStatus.FINISHED }) runningTimers.firstOrNull()?.id else null

        val updated = activeTimers.map { timer ->
            if (timer.status == TimerStatus.RUNNING && (timer.endTimeMillis <= now + 2000L || timer.id == fallbackId)) {
                timer.copy(remainingMillis = 0L, status = TimerStatus.FINISHED)
            } else timer
        }
        repository.saveActiveTimers(updated)
        stateChangeListener?.invoke()
        refreshServiceState()
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

    private fun handleRemoveTimerAction(timerId: String) {
        val repository = TimerRepository(applicationContext)
        val activeTimers = repository.getActiveTimers()
        val removed = activeTimers.firstOrNull { it.id == timerId } ?: return
        val updated = activeTimers.filterNot { it.id == timerId }

        // Silence the alarm (and close the red screen) once no finished timers remain.
        // If others are still finished, refreshServiceState() keeps the alarm going for them.
        if (removed.status == TimerStatus.FINISHED && updated.none { it.status == TimerStatus.FINISHED }) {
            stopAlarm()
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
                triggerAlarm()

                val label = finishedTimers.first().displayOriginalLabel
                if (runningTimers.isNotEmpty()) {
                    val nextTimer = runningTimers.first()
                    scheduleAlarmManager(nextTimer.endTimeMillis)
                    startTickerLoop()

                    startForegroundNotification(label, "Time's up!", timerId = finishedTimers.first().id, endTimeMillis = nextTimer.endTimeMillis, isCountingDown = false, isAlarm = true)
                } else {
                    stopTickerLoop()
                    startForegroundNotification(label, "Time's up!", timerId = finishedTimers.first().id, isCountingDown = false, isAlarm = true)
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

                startForegroundNotification(title, content, timerId = first.id, endTimeMillis = first.endTimeMillis, isCountingDown = true, isAlarm = false)
                scheduleAlarmManager(first.endTimeMillis)
                startTickerLoop()
            }
            else -> {
                stopAlarm()
                stopTickerLoop()
                cancelAlarmManager()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                } catch (e: Exception) {
                    // Ignore
                }
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

                updateNotification(title, content, timerId = first.id, endTimeMillis = first.endTimeMillis, isCountingDown = true, isAlarm = false)
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
        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(this, com.jkb016.timer.AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val showOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        val showPendingIntent = PendingIntent.getActivity(
            this, 1005, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            showOptions
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
        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getBroadcast(
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
        val soundUri = android.net.Uri.parse("android.resource://" + packageName + "/" + com.jkb016.timer.R.raw.sound)

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
                mediaPlayer = MediaPlayer.create(applicationContext, com.jkb016.timer.R.raw.sound)?.apply {
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
        isAlarm: Boolean = false,
        timerId: String? = null
    ) {
        val notification = createNotification(title, content, endTimeMillis, isCountingDown, isAlarm, timerId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException or SecurityException
        }
    }

    fun updateNotification(
        title: String,
        content: String,
        endTimeMillis: Long = 0L,
        isCountingDown: Boolean = false,
        isAlarm: Boolean = false,
        timerId: String? = null
    ) {
        try {
            val notification = createNotification(title, content, endTimeMillis, isCountingDown, isAlarm, timerId)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Ignore notification error
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
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

        val alarmIntent = Intent(this, com.jkb016.timer.AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(com.jkb016.timer.AlarmActivity.EXTRA_TIMER_NAME, firstName)
        }
        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        val pendingIntent = PendingIntent.getActivity(
            this, 1004, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions
        )
        val senderOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        // With the overlay permission the direct launch below succeeds (locked or not), so skip the
        // heads-up notification up front instead of flashing it until the activity reports in.
        // If the red screen hasn't reported visible shortly after, fall back to the alerting
        // notification (full-screen intent) so the alarm is never silent on screen.
        if (!alarmScreenShown && android.provider.Settings.canDrawOverlays(this)) {
            alarmScreenShown = true
            alarmLaunchCheckJob?.cancel()
            alarmLaunchCheckJob = serviceScope.launch {
                delay(3000)
                alarmScreenShown = false
                ensureForegroundNotification()
            }
        }

        try {
            pendingIntent.send(this, 0, null, null, null, null, senderOptions)
        } catch (e: Exception) {
            try {
                startActivity(alarmIntent)
            } catch (e2: Exception) {
                // Ignore
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
        alarmScreenShown = false
        alarmLaunchCheckJob?.cancel()
        alarmLaunchCheckJob = null
        stopAlarmSound()
        vibrator?.cancel()
        releaseWakeLock()
        val stopBroadcast = Intent(ACTION_ALARM_STOPPED).apply {
            setPackage(packageName)
        }
        sendBroadcast(stopBroadcast)
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Ignore
        }
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
        isAlarm: Boolean = false,
        timerId: String? = null
    ): Notification {
        // Only alert (heads-up / full-screen intent) while the red screen isn't already showing.
        val alert = isAlarm && !alarmScreenShown
        val channelId = if (alert) CHANNEL_ALARM_ID else CHANNEL_ID
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmActivityIntent = Intent(this, com.jkb016.timer.AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(com.jkb016.timer.AlarmActivity.EXTRA_TIMER_NAME, title)
        }
        val fullScreenOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 1005, alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            fullScreenOptions
        )

        // Full-screen intents are only honoured on a high-importance channel, and only when the
        // notification is allowed to alert (onlyAlertOnce would suppress it on an update).
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(if (isAlarm) fullScreenPendingIntent else pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alert)
            .setUsesChronometer(false)
            .setShowWhen(false)
            .setSound(null)
            .setPriority(if (alert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)

        // Stop button removes the timer this notification is showing (finished or counting down).
        if (timerId != null) {
            val removeIntent = Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_REMOVE_TIMER
                putExtra(EXTRA_TIMER_ID, timerId)
            }
            val removePendingIntent = PendingIntent.getService(
                this, 1006, removeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", removePendingIntent)

            // Sub text is shown in the notification header, next to the app name.
            TimerRepository(applicationContext).getActiveTimers().firstOrNull { it.id == timerId }?.let { timer ->
                builder.setSubText("Started ${TimerViewModel.formatClockTime(this, timer.createdAt, withSeconds = false)}")
            }
        }

        if (alert) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

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
                setSound(null, null)
            }
            manager.createNotificationChannel(lowChannel)

            // Sound and vibration are driven by the service itself, so the channel stays silent.
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Timer Alarm Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alert when a timer finishes"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
                setBypassDnd(true)
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
        const val ACTION_ALARM_TRIGGER = "com.jkb016.timer.ACTION_ALARM_TRIGGER"
        const val ACTION_STOP_TIMER = "com.jkb016.timer.ACTION_STOP_TIMER"
        const val ACTION_ALARM_STOPPED = "com.jkb016.timer.ACTION_ALARM_STOPPED"
        const val ACTION_ALARM_SHOWN = "com.jkb016.timer.ACTION_ALARM_SHOWN"
        const val ACTION_ALARM_HIDDEN = "com.jkb016.timer.ACTION_ALARM_HIDDEN"
        const val ACTION_ADD_TIME = "com.jkb016.timer.ACTION_ADD_TIME"
        const val EXTRA_ADD_SECONDS = "extra_add_seconds"
        const val ACTION_REMOVE_TIMER = "com.jkb016.timer.ACTION_REMOVE_TIMER"
        const val EXTRA_TIMER_ID = "extra_timer_id"
        private const val CHANNEL_ID = "timer_service_channel"
        private const val CHANNEL_ALARM_ID = "timer_alarm_channel_v5"
        private const val NOTIFICATION_ID = 1001
    }
}
