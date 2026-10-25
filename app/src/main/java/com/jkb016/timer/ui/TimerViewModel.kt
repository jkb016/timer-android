package com.jkb016.timer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.format.DateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jkb016.timer.data.ActiveTimerState
import com.jkb016.timer.data.TimerHistoryItem
import com.jkb016.timer.data.TimerRepository
import com.jkb016.timer.data.TimerStatus
import com.jkb016.timer.service.TimerForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TimerRepository(application.applicationContext)

    private val _activeTimers = MutableStateFlow<List<ActiveTimerState>>(emptyList())
    val activeTimers: StateFlow<List<ActiveTimerState>> = _activeTimers.asStateFlow()

    private val _historyList = MutableStateFlow<List<TimerHistoryItem>>(emptyList())
    val historyList: StateFlow<List<TimerHistoryItem>> = _historyList.asStateFlow()

    // Keypad input state in HH:MM:SS format string digits (up to 6 digits)
    private val _keypadInput = MutableStateFlow("")
    val keypadInput: StateFlow<String> = _keypadInput.asStateFlow()

    private val _highlightedTimerId = MutableStateFlow<String?>(null)
    val highlightedTimerId: StateFlow<String?> = _highlightedTimerId.asStateFlow()

    private var highlightJob: Job? = null
    private var timerLoopJob: Job? = null
    private var boundService: TimerForegroundService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerForegroundService.LocalBinder
            boundService = binder.getService()
            isServiceBound = true
            boundService?.setStateChangeListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    reloadStateFromRepository()
                }
            }
            reloadStateFromRepository()
            updateForegroundNotification()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.setStateChangeListener(null)
            boundService = null
            isServiceBound = false
        }
    }

    fun reloadStateFromRepository() {
        val restoredTimers = sortActiveTimers(repository.getActiveTimers())
        _activeTimers.value = restoredTimers
        if (restoredTimers.any { it.status == TimerStatus.RUNNING }) {
            startTimerLoopIfNeeded()
        } else {
            timerLoopJob?.cancel()
            timerLoopJob = null
        }
    }

    init {
        loadHistory()
        restoreState()
        bindTimerService()
    }

    private fun bindTimerService() {
        try {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, TimerForegroundService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    fun loadHistory() {
        _historyList.value = repository.getHistory().sortedBy { it.totalSeconds }
    }

    private fun sortActiveTimers(list: List<ActiveTimerState>): List<ActiveTimerState> {
        // Requirement: When timer completes (status == FINISHED), move to TOP of list
        return list.sortedWith(
            compareByDescending<ActiveTimerState> { it.status == TimerStatus.FINISHED }
                .thenByDescending { it.createdAt }
        )
    }

    fun startTimerWithDuration(totalSeconds: Long, label: String = "") {
        if (totalSeconds <= 0) return

        // Save duration to history (DEDUPLICATED & SORTED BY TIME)
        _historyList.value = repository.saveToHistory(totalSeconds, label)

        val now = System.currentTimeMillis()
        val newTimer = ActiveTimerState(
            originalSeconds = totalSeconds,
            currentTotalSeconds = totalSeconds,
            remainingMillis = totalSeconds * 1000L,
            status = TimerStatus.RUNNING,
            label = label,
            createdAt = now,
            endTimeMillis = now + (totalSeconds * 1000L)
        )

        val activeOnly = _activeTimers.value.filterNot { it.status == TimerStatus.FINISHED }
        val updatedList = sortActiveTimers(activeOnly + newTimer)
        _activeTimers.value = updatedList
        repository.saveActiveTimers(updatedList)

        _highlightedTimerId.value = newTimer.id
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(3000)
            if (_highlightedTimerId.value == newTimer.id) {
                _highlightedTimerId.value = null
            }
        }

        val context = getApplication<Application>().applicationContext
        val serviceIntent = Intent(context, TimerForegroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)

        updateForegroundNotification()
        startTimerLoopIfNeeded()
    }

    fun startFromKeypad() {
        val totalSeconds = parseKeypadInputToSeconds(_keypadInput.value)
        if (totalSeconds > 0) {
            startTimerWithDuration(totalSeconds)
            _keypadInput.value = ""
        }
    }

    fun appendKeypadInput(digits: String) {
        if (_keypadInput.value.isEmpty() && digits.all { it == '0' }) return
        val availableSpace = 6 - _keypadInput.value.length
        if (availableSpace > 0) {
            val toAppend = digits.take(availableSpace)
            _keypadInput.value += toAppend
        }
    }

    fun appendKeypadDigit(digit: Char) {
        appendKeypadInput(digit.toString())
    }

    fun deleteKeypadDigit() {
        if (_keypadInput.value.isNotEmpty()) {
            _keypadInput.value = _keypadInput.value.dropLast(1)
        }
    }

    fun clearKeypadInput() {
        _keypadInput.value = ""
    }

    fun pauseTimer(id: String) {
        val updatedList = sortActiveTimers(
            _activeTimers.value.map { timer ->
                if (timer.id == id && timer.status == TimerStatus.RUNNING) {
                    timer.copy(status = TimerStatus.PAUSED)
                } else timer
            }
        )
        _activeTimers.value = updatedList
        repository.saveActiveTimers(updatedList)
        updateForegroundNotification()
    }

    fun resumeTimer(id: String) {
        val now = System.currentTimeMillis()
        val updatedList = sortActiveTimers(
            _activeTimers.value.map { timer ->
                if (timer.id == id && timer.status == TimerStatus.PAUSED && timer.remainingMillis > 0) {
                    timer.copy(
                        status = TimerStatus.RUNNING,
                        endTimeMillis = now + timer.remainingMillis
                    )
                } else timer
            }
        )
        _activeTimers.value = updatedList
        repository.saveActiveTimers(updatedList)
        val context = getApplication<Application>().applicationContext
        val serviceIntent = Intent(context, TimerForegroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)

        updateForegroundNotification()
        startTimerLoopIfNeeded()
    }

    fun resetTimer(id: String) {
        val updatedList = sortActiveTimers(_activeTimers.value.filterNot { it.id == id })
        _activeTimers.value = updatedList
        repository.saveActiveTimers(updatedList)

        if (updatedList.none { it.status == TimerStatus.FINISHED }) {
            boundService?.stopAlarm()
        }
        if (updatedList.isEmpty()) {
            boundService?.stopForegroundService()
        } else {
            updateForegroundNotification()
        }
    }

    fun clearAllTimers() {
        _activeTimers.value = emptyList()
        repository.saveActiveTimers(emptyList())
        boundService?.stopAlarm()
        boundService?.stopForegroundService()
    }

    fun addTime(id: String, secondsToAdd: Long) {
        val now = System.currentTimeMillis()
        val millisToAdd = secondsToAdd * 1000L
        val updatedList = sortActiveTimers(
            _activeTimers.value.map { timer ->
                if (timer.id == id) {
                    when (timer.status) {
                        TimerStatus.RUNNING -> {
                            val newRemaining = timer.remainingMillis + millisToAdd
                            val newTotal = timer.currentTotalSeconds + secondsToAdd
                            timer.copy(
                                currentTotalSeconds = newTotal,
                                remainingMillis = newRemaining,
                                endTimeMillis = timer.endTimeMillis + millisToAdd
                            )
                        }
                        TimerStatus.PAUSED -> {
                            val newRemaining = timer.remainingMillis + millisToAdd
                            val newTotal = timer.currentTotalSeconds + secondsToAdd
                            timer.copy(
                                currentTotalSeconds = newTotal,
                                remainingMillis = newRemaining
                            )
                        }
                        TimerStatus.FINISHED -> {
                            boundService?.stopAlarm()
                            timer.copy(
                                currentTotalSeconds = secondsToAdd,
                                remainingMillis = millisToAdd,
                                status = TimerStatus.RUNNING,
                                endTimeMillis = now + millisToAdd
                            )
                        }
                        else -> timer
                    }
                } else timer
            }
        )
        _activeTimers.value = updatedList
        repository.saveActiveTimers(updatedList)

        if (updatedList.any { it.status == TimerStatus.RUNNING }) {
            val context = getApplication<Application>().applicationContext
            val serviceIntent = Intent(context, TimerForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }

        updateForegroundNotification()
        startTimerLoopIfNeeded()
    }

    fun addOneMinute(id: String) {
        addTime(id, 60L)
    }


    fun deleteHistoryItem(id: String) {
        _historyList.value = repository.deleteFromHistory(id)
    }

    fun clearAllHistory() {
        _historyList.value = repository.clearHistory()
        // Remove all PAUSED and FINISHED timers from top active timers list
        val remainingRunning = _activeTimers.value.filter { it.status == TimerStatus.RUNNING }
        _activeTimers.value = remainingRunning
        repository.saveActiveTimers(remainingRunning)

        if (remainingRunning.isEmpty()) {
            boundService?.stopAlarm()
            boundService?.stopForegroundService()
        } else {
            updateForegroundNotification()
        }
    }

    private fun startTimerLoopIfNeeded() {
        if (timerLoopJob?.isActive == true) return
        timerLoopJob = viewModelScope.launch {
            while (_activeTimers.value.any { it.status == TimerStatus.RUNNING }) {
                delay(100)
                val now = System.currentTimeMillis()
                var hasNewlyFinished = false

                val mappedList = _activeTimers.value.map { timer ->
                    if (timer.status == TimerStatus.RUNNING) {
                        val newRem = (timer.endTimeMillis - now).coerceAtLeast(0L)
                        if (newRem <= 0L) {
                            hasNewlyFinished = true
                            timer.copy(remainingMillis = 0L, status = TimerStatus.FINISHED)
                        } else {
                            timer.copy(remainingMillis = newRem)
                        }
                    } else timer
                }

                // Re-sort so finished timers instantly move to TOP of list
                val sortedList = sortActiveTimers(mappedList)
                _activeTimers.value = sortedList

                if (hasNewlyFinished) {
                    boundService?.triggerAlarm()
                    updateForegroundNotification()
                }

                if (now % 1000 < 120) {
                    repository.saveActiveTimers(sortedList)
                    updateForegroundNotification()
                }
            }
        }
    }

    private fun updateForegroundNotification() {
        boundService?.refreshServiceState()
    }

    private fun restoreState() {
        val restoredTimers = sortActiveTimers(repository.getActiveTimers())
        _activeTimers.value = restoredTimers
        if (restoredTimers.any { it.status == TimerStatus.RUNNING }) {
            startTimerLoopIfNeeded()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound) {
            val context = getApplication<Application>().applicationContext
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore unbind exception
            }
        }
    }

    companion object {
        fun parseKeypadInputToSeconds(input: String): Long {
            val padded = input.padStart(6, '0')
            val h = padded.substring(0, 2).toLongOrNull() ?: 0L
            val m = padded.substring(2, 4).toLongOrNull() ?: 0L
            val s = padded.substring(4, 6).toLongOrNull() ?: 0L
            return h * 3600 + m * 60 + s
        }

        fun formatSeconds(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> String.format("%d:%02d:%02d", h, m, s)
                else -> String.format("%d:%02d", m, s)
            }
        }

        /** Formats a timestamp as a time of day with seconds, following the device's 12/24-hour setting. */
        fun formatClockTime(context: Context, millis: Long): String {
            val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
            return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
        }

        /** Like [formatRemainingTime] but rounds down, for time counting up (e.g. since a timer ended). */
        fun formatElapsedTime(millis: Long): String {
            val totalSec = millis.coerceAtLeast(0L) / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> String.format("%02d:%02d:%02d", h, m, s)
                else -> String.format("%02d:%02d", m, s)
            }
        }

        fun formatRemainingTime(millis: Long): String {
            val totalSec = (millis + 999) / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> String.format("%02d:%02d:%02d", h, m, s)
                else -> String.format("%02d:%02d", m, s)
            }
        }
    }
}
