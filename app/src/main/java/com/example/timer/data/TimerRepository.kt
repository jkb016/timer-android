package com.example.timer.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TimerRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_HISTORY = "timer_history_json"
        private const val KEY_ACTIVE_TIMERS = "key_active_timers_json"
    }

    /**
     * Retrieves history items sorted by time (totalSeconds ascending). Guaranteed no duplicates.
     */
    fun getHistory(): List<TimerHistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return defaultPresets()
        return try {
            val type = object : TypeToken<List<TimerHistoryItem>>() {}.type
            val parsed: List<TimerHistoryItem> = gson.fromJson(json, type) ?: emptyList()
            deduplicateList(parsed).sortedBy { it.totalSeconds }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Saves duration to history while enforcing NO DUPLICATES requirement.
     * Items are stored and returned sorted by time.
     */
    fun saveToHistory(totalSeconds: Long, label: String = ""): List<TimerHistoryItem> {
        if (totalSeconds <= 0) return getHistory()
        val currentList = getHistory().toMutableList()

        val existingIndex = currentList.indexOfFirst { it.totalSeconds == totalSeconds }
        if (existingIndex != -1) {
            val existing = currentList.removeAt(existingIndex)
            val updated = existing.copy(
                label = if (label.isNotEmpty()) label else existing.label,
                timestamp = System.currentTimeMillis()
            )
            currentList.add(updated)
        } else {
            val newItem = TimerHistoryItem(
                totalSeconds = totalSeconds,
                label = label,
                timestamp = System.currentTimeMillis()
            )
            currentList.add(newItem)
        }

        saveHistoryList(currentList)
        return currentList.sortedBy { it.totalSeconds }
    }

    fun deleteFromHistory(id: String): List<TimerHistoryItem> {
        val currentList = getHistory().filterNot { it.id == id }.sortedBy { it.totalSeconds }
        saveHistoryList(currentList)
        return currentList
    }

    fun clearHistory(): List<TimerHistoryItem> {
        saveHistoryList(emptyList())
        return emptyList()
    }

    private fun saveHistoryList(list: List<TimerHistoryItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    private fun deduplicateList(list: List<TimerHistoryItem>): List<TimerHistoryItem> {
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<TimerHistoryItem>()
        for (item in list) {
            if (seen.add(item.totalSeconds)) {
                result.add(item)
            }
        }
        return result
    }

    private fun defaultPresets(): List<TimerHistoryItem> {
        val defaults = listOf(
            TimerHistoryItem(totalSeconds = 60, label = "1 min"),
            TimerHistoryItem(totalSeconds = 300, label = "5 min"),
            TimerHistoryItem(totalSeconds = 600, label = "10 min"),
            TimerHistoryItem(totalSeconds = 900, label = "15 min"),
            TimerHistoryItem(totalSeconds = 1800, label = "30 min")
        )
        saveHistoryList(defaults)
        return defaults.sortedBy { it.totalSeconds }
    }

    fun saveActiveTimers(timers: List<ActiveTimerState>) {
        val json = gson.toJson(timers)
        prefs.edit().putString(KEY_ACTIVE_TIMERS, json).apply()
    }

    fun getActiveTimers(): List<ActiveTimerState> {
        val json = prefs.getString(KEY_ACTIVE_TIMERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ActiveTimerState>>() {}.type
            val list: List<ActiveTimerState> = gson.fromJson(json, type) ?: emptyList()
            val now = System.currentTimeMillis()
            var modified = false
            val updated = list.map { timer ->
                if (timer.status == TimerStatus.RUNNING) {
                    val rem = timer.endTimeMillis - now
                    if (rem > 0) {
                        modified = true
                        timer.copy(remainingMillis = rem)
                    } else {
                        modified = true
                        timer.copy(remainingMillis = 0L, status = TimerStatus.FINISHED)
                    }
                } else {
                    timer
                }
            }
            if (modified) {
                saveActiveTimers(updated)
            }
            updated
        } catch (e: Exception) {
            emptyList()
        }
    }
}
